use futures::StreamExt;
use jni::{
    objects::{JClass, JString},
    sys::jstring,
    JNIEnv,
};
use libp2p::{
    core::transport::ListenerId,
    identify, identity, kad,
    multiaddr::Protocol,
    noise, request_response,
    swarm::{
        dial_opts::{DialOpts, PeerCondition},
        NetworkBehaviour, SwarmEvent,
    },
    tcp, yamux, Multiaddr, PeerId, StreamProtocol, SwarmBuilder,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{mpsc as std_mpsc, Mutex, OnceLock},
    time::{Duration, Instant},
};
use tokio::sync::mpsc;

const MAX_PAYLOAD: usize = 550_000;
const PROTOCOL: &str = "/app.p2p/exchange/3";

#[derive(Debug, Serialize, Deserialize)]
struct Request {
    payload: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct Response {
    status: String,
}

#[derive(NetworkBehaviour)]
struct Behaviour {
    exchange: request_response::cbor::Behaviour<Request, Response>,
    discovery: kad::Behaviour<kad::store::MemoryStore>,
    identify: identify::Behaviour,
}

#[derive(Deserialize)]
#[serde(tag = "op")]
enum Command {
    #[serde(rename = "bind")]
    Bind { addresses: Vec<String> },
    #[serde(rename = "seed")]
    Seed {
        peer: String,
        addresses: Vec<String>,
    },
    #[serde(rename = "lookup")]
    Lookup { key: String },
    #[serde(rename = "punch")]
    Punch {
        peer: String,
        addresses: Vec<String>,
        listener: bool,
    },
    #[serde(rename = "publish")]
    Publish { key: String, value: String },
    #[serde(rename = "route")]
    Route {
        peer: String,
        addresses: Vec<String>,
    },
    #[serde(rename = "send")]
    Send {
        ticket: String,
        peer: String,
        payload: String,
    },
    #[serde(rename = "ack")]
    Acknowledge { ticket: u64, status: String },
}

struct Engine {
    key: identity::Keypair,
    commands: mpsc::Sender<Command>,
    events: std_mpsc::Receiver<Value>,
    worker: std::thread::JoinHandle<()>,
}

static ENGINE: OnceLock<Mutex<Option<Engine>>> = OnceLock::new();

fn hex_bytes(encoded: &str) -> Result<Vec<u8>, String> {
    if encoded.len() > 512 || encoded.len() % 2 != 0 || !encoded.is_ascii() {
        return Err("Invalid identity".into());
    }
    (0..encoded.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16))
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| "Invalid hex".into())
}

fn identity_key(encoded: &str) -> Result<identity::Keypair, String> {
    identity::Keypair::from_protobuf_encoding(&hex_bytes(encoded)?)
        .map_err(|error| error.to_string())
}

fn launch(key: identity::Keypair, port: u16, addresses: Vec<String>) -> Result<Engine, String> {
    let (commands, receiver) = mpsc::channel(128);
    let (events, event_reader) = std_mpsc::sync_channel(256);
    let (ready, result) = std_mpsc::sync_channel(1);
    let thread_key = key.clone();
    let worker = std::thread::Builder::new()
        .name("p2p-engine".into())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    let _ = ready.send(Err(error.to_string()));
                    return;
                }
            };
            runtime.block_on(run(thread_key, port, addresses, receiver, events, ready));
        })
        .map_err(|error| error.to_string())?;
    result
        .recv_timeout(Duration::from_secs(8))
        .map_err(|error| error.to_string())??;
    Ok(Engine {
        key,
        commands,
        events: event_reader,
        worker,
    })
}

fn admissible(addr: &Multiaddr, peer: PeerId) -> Option<Multiaddr> {
    let parts: Vec<_> = addr.iter().collect();
    if !(3..=4).contains(&parts.len()) {
        return None;
    }
    match parts[0] {
        Protocol::Ip4(ip) if !ip.is_unspecified() && !ip.is_multicast() => (),
        Protocol::Ip6(ip) if !ip.is_unspecified() && !ip.is_multicast() => (),
        _ => return None,
    }
    let (route, suffix) = match parts.as_slice() {
        [_, Protocol::Tcp(port), suffix @ Protocol::P2p(_)] if *port != 0 => (true, suffix),
        [_, Protocol::Udp(port), Protocol::QuicV1, suffix @ Protocol::P2p(_)] if *port != 0 => {
            (true, suffix)
        }
        _ => return None,
    };
    if !route || suffix != &Protocol::P2p(peer) {
        return None;
    }
    Some(addr.clone())
}

async fn run(
    key: identity::Keypair,
    port: u16,
    addresses: Vec<String>,
    mut commands: mpsc::Receiver<Command>,
    events: std_mpsc::SyncSender<Value>,
    ready: std_mpsc::SyncSender<Result<(), String>>,
) {
    let local = key.public().to_peer_id();
    let result = SwarmBuilder::with_existing_identity(key)
        .with_tokio()
        .with_tcp(
            tcp::Config::default().nodelay(true),
            noise::Config::new,
            yamux::Config::default,
        )
        .map_err(|error| error.to_string())
        .and_then(|builder| {
            builder
                .with_quic()
                .with_behaviour(|key| {
                    let mut config = kad::Config::new(StreamProtocol::new("/app.p2p/discovery/1"));
                    config
                        .set_query_timeout(Duration::from_secs(9))
                        .set_record_ttl(Some(Duration::from_secs(180)))
                        .set_replication_interval(None)
                        .set_publication_interval(None);
                    let store = kad::store::MemoryStore::with_config(
                        local,
                        kad::store::MemoryStoreConfig {
                            max_records: 512,
                            max_value_bytes: 4096,
                            max_provided_keys: 16,
                            max_providers_per_key: 4,
                        },
                    );
                    let discovery = kad::Behaviour::with_config(local, store, config);
                    Behaviour {
                        exchange: request_response::cbor::Behaviour::with_codec(
                            request_response::cbor::codec::Codec::default()
                                .set_request_size_maximum(MAX_PAYLOAD as u64 + 512)
                                .set_response_size_maximum(512),
                            [(
                                StreamProtocol::new(PROTOCOL),
                                request_response::ProtocolSupport::Full,
                            )],
                            request_response::Config::default()
                                .with_request_timeout(Duration::from_secs(15))
                                .with_max_concurrent_streams(24),
                        ),
                        discovery,
                        identify: identify::Behaviour::new(identify::Config::new(
                            "/app.p2p/identity/1".into(),
                            key.public(),
                        )),
                    }
                })
                .map_err(|error| error.to_string())
        });
    let mut swarm = match result {
        Ok(builder) => builder
            .with_swarm_config(|config| {
                config.with_idle_connection_timeout(Duration::from_secs(45))
            })
            .build(),
        Err(error) => {
            let _ = ready.send(Err(error));
            return;
        }
    };
    let mut listeners = HashMap::new();
    bind(&mut swarm, &mut listeners, &addresses, port);
    let _ = ready.send(Ok(()));
    let mut inbound = HashMap::new();
    let mut outbound = HashMap::new();
    let mut next_ticket = 0_u64;
    let mut lookup_keys = HashMap::new();
    let mut cleanup = tokio::time::interval(Duration::from_secs(10));

    loop {
        tokio::select! {
            cmd = commands.recv() => match cmd {
                Some(Command::Bind { addresses }) => bind(&mut swarm, &mut listeners, &addresses, port),
                Some(Command::Seed { peer, addresses }) => {
                    if let Ok(peer) = peer.parse::<PeerId>() {
                        for address in addresses.iter().take(8).filter_map(|value| value.parse::<Multiaddr>().ok()) {
                            if let Some(route) = admissible(&address, peer) {
                                swarm.behaviour_mut().discovery.add_address(&peer, route);
                            }
                        }
                    }
                }
                Some(Command::Route { peer, addresses }) => {
                    if let Ok(peer) = peer.parse::<PeerId>() {
                        for address in addresses.iter().take(12).filter_map(|value| value.parse::<Multiaddr>().ok()) {
                            if let Some(route) = admissible(&address, peer) { swarm.add_peer_address(peer, route); }
                        }
                    }
                }
                Some(Command::Punch { peer, addresses, listener }) => {
                    if let Ok(peer) = peer.parse::<PeerId>() {
                        let routes: Vec<_> = addresses.iter().take(2).filter_map(|value| value.parse::<Multiaddr>().ok())
                            .filter(|address| address.iter().any(|part| part == Protocol::QuicV1))
                            .filter_map(|address| admissible(&address, peer)).collect();
                        if !routes.is_empty() {
                            let options = DialOpts::peer_id(peer).addresses(routes).condition(PeerCondition::Disconnected);
                            let options = if listener { options.override_role() } else { options };
                            let _ = swarm.dial(options.build());
                        }
                    }
                }
                Some(Command::Publish { key, value }) if key.len() == 64 && value.len() < 4000 => {
                    let record = kad::Record {
                        key: kad::RecordKey::new(&key),
                        value: value.into_bytes(), publisher: Some(local),
                        expires: Some(Instant::now() + Duration::from_secs(180)),
                    };
                    let _ = swarm.behaviour_mut().discovery.put_record(record, kad::Quorum::One);
                }
                Some(Command::Lookup { key }) if key.len() == 64 && lookup_keys.len() < 24 => {
                    if !lookup_keys.values().any(|active| active == &key) {
                        let query = swarm.behaviour_mut().discovery.get_record(kad::RecordKey::new(&key));
                        lookup_keys.insert(query, key);
                    }
                }
                Some(Command::Lookup { .. } | Command::Publish { .. }) => (),
                Some(Command::Send { ticket, peer, payload }) => {
                    if let Ok(peer) = peer.parse::<PeerId>() {
                        if payload.len() <= MAX_PAYLOAD && outbound.len() < 32 {
                            let id = swarm.behaviour_mut().exchange.send_request(&peer, Request { payload });
                            outbound.insert(id, ticket);
                        } else {
                            let _ = events.try_send(json!({"kind":"result","ticket":ticket,"status":"retry"}));
                        }
                    }
                }
                Some(Command::Acknowledge { ticket, status }) => {
                    if let Some((channel, _)) = inbound.remove(&ticket) {
                        let _ = swarm.behaviour_mut().exchange.send_response(channel, Response { status });
                    }
                }
                None => break,
            },
            event = swarm.select_next_some() => match event {
                SwarmEvent::ConnectionEstablished { endpoint, .. } if endpoint.is_listener() => {
                    if let libp2p::core::ConnectedPoint::Listener { local_addr, .. } = endpoint {
                        // An authenticated inbound session has reached this listener.
                        swarm.add_external_address(local_addr);
                    }
                }
                SwarmEvent::ExpiredListenAddr { address, .. } => {
                    swarm.remove_external_address(&address);
                }
                SwarmEvent::NewListenAddr { address, .. } => {
                    let _ = events.try_send(json!({"kind":"listen","address":address.to_string()}));
                }
                SwarmEvent::Behaviour(BehaviourEvent::Exchange(event)) => match event {
                    request_response::Event::Message { peer, message, .. } => match message {
                        request_response::Message::Request { request, channel, .. } => {
                            if inbound.len() >= 64 || request.payload.len() > MAX_PAYLOAD {
                                let _ = swarm.behaviour_mut().exchange.send_response(channel, Response { status:"retry".into() });
                                continue;
                            }
                            next_ticket = next_ticket.wrapping_add(1);
                            if events.try_send(json!({"kind":"incoming","ticket":next_ticket,"peer":peer.to_string(),"payload":request.payload})).is_ok() {
                                inbound.insert(next_ticket, (channel, Instant::now()));
                            }
                        }
                        request_response::Message::Response { request_id, response } => {
                            if let Some(ticket) = outbound.remove(&request_id) {
                                let _ = events.try_send(json!({"kind":"result","ticket":ticket,"status":response.status}));
                            }
                        }
                    }
                    request_response::Event::OutboundFailure { request_id, .. } => {
                        if let Some(ticket) = outbound.remove(&request_id) {
                            let _ = events.try_send(json!({"kind":"result","ticket":ticket,"status":"retry"}));
                        }
                    }
                    _ => ()
                },
                SwarmEvent::Behaviour(BehaviourEvent::Discovery(kad::Event::OutboundQueryProgressed {
                    id, result: kad::QueryResult::GetRecord(result), step, ..
                })) => {
                    if let Some(key) = lookup_keys.get(&id) {
                        if let Ok(kad::GetRecordOk::FoundRecord(found)) = result {
                            if let Ok(value) = String::from_utf8(found.record.value) {
                                let _ = events.try_send(json!({"kind":"rendezvous","key":key,"value":value}));
                            }
                        }
                        if step.last { lookup_keys.remove(&id); }
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received { peer_id, info, .. })) => {
                    let routes: Vec<_> = info.listen_addrs.into_iter().take(12)
                        .filter_map(|mut address| {
                            if !matches!(address.iter().last(), Some(Protocol::P2p(_))) {
                                address.push(Protocol::P2p(peer_id));
                            }
                            admissible(&address, peer_id)
                        }).collect();
                    let is_seed = info.protocols.contains(&StreamProtocol::new("/app.p2p/discovery/1"));
                    let _ = events.try_send(json!({
                        "kind":"peer","peer":peer_id.to_string(),
                        "addresses":routes.iter().map(ToString::to_string).collect::<Vec<_>>(),
                        "observed":info.observed_addr.to_string(), "seed":is_seed
                    }));
                    if is_seed {
                        for route in &routes {
                            swarm.behaviour_mut().discovery.add_address(&peer_id, route.clone());
                        }
                    }
                    for route in routes { swarm.add_peer_address(peer_id, route); }
                }
                _ => ()
            },
            _ = cleanup.tick() => inbound.retain(|_, (_, started)| started.elapsed() < Duration::from_secs(20))
        }
    }
}

fn bind(
    swarm: &mut libp2p::Swarm<Behaviour>,
    listeners: &mut HashMap<Multiaddr, ListenerId>,
    addresses: &[String],
    port: u16,
) {
    let mut wanted = std::collections::HashSet::new();
    for ip in addresses
        .iter()
        .take(8)
        .filter_map(|text| text.parse::<std::net::IpAddr>().ok())
    {
        if ip.is_unspecified() || ip.is_multicast() {
            continue;
        }
        let family = if ip.is_ipv4() { "ip4" } else { "ip6" };
        for transport in [format!("udp/{port}/quic-v1"), format!("tcp/{port}")] {
            if let Ok(address) = format!("/{family}/{ip}/{transport}").parse::<Multiaddr>() {
                wanted.insert(address.clone());
                if !listeners.contains_key(&address) {
                    if let Ok(id) = swarm.listen_on(address.clone()) {
                        listeners.insert(address, id);
                    }
                }
            }
        }
    }
    listeners.retain(|address, id| {
        if wanted.contains(address) {
            true
        } else {
            swarm.remove_external_address(address);
            swarm.remove_listener(*id);
            false
        }
    });
}

fn output(env: &mut JNIEnv, value: impl AsRef<str>) -> jstring {
    env.new_string(value.as_ref())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_newIdentity(
    mut env: JNIEnv,
    _: JClass,
) -> jstring {
    let key = identity::Keypair::generate_ed25519();
    let result = key
        .to_protobuf_encoding()
        .ok()
        .map(|bytes| {
            let secret: String = bytes.iter().map(|byte| format!("{byte:02x}")).collect();
            json!({"secret":secret,"peer":key.public().to_peer_id().to_string()}).to_string()
        })
        .unwrap_or_default();
    output(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_start(
    mut env: JNIEnv,
    _: JClass,
    key: JString,
    port: i32,
    addresses: JString,
) -> jstring {
    let result = (|| {
        let secret: String = env
            .get_string(&key)
            .map_err(|error| error.to_string())?
            .into();
        let key = identity_key(&secret)?;
        let peer = key.public().to_peer_id().to_string();
        let addresses: String = env
            .get_string(&addresses)
            .map_err(|error| error.to_string())?
            .into();
        let addresses = serde_json::from_str(&addresses).map_err(|error| error.to_string())?;
        let mut engine = ENGINE
            .get_or_init(|| Mutex::new(None))
            .lock()
            .map_err(|_| "Engine unavailable")?;
        if engine.is_none() {
            *engine = Some(launch(
                key,
                u16::try_from(port).map_err(|_| "Invalid port")?,
                addresses,
            )?);
        }
        Ok::<_, String>(peer)
    })();
    output(
        &mut env,
        match result {
            Ok(peer) => json!({"peer":peer}).to_string(),
            Err(error) => json!({"error":error}).to_string(),
        },
    )
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_identityAddress(
    mut env: JNIEnv,
    _: JClass,
    encoded: JString,
) -> jstring {
    let value = (|| {
        let encoded: String = env.get_string(&encoded).ok()?.into();
        Some(
            identity_key(&encoded)
                .ok()?
                .public()
                .to_peer_id()
                .to_string(),
        )
    })()
    .unwrap_or_default();
    output(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_stop(_: JNIEnv, _: JClass) {
    let Some(mut guard) = ENGINE.get().and_then(|engine| engine.lock().ok()) else {
        return;
    };
    if let Some(Engine {
        commands, worker, ..
    }) = guard.take()
    {
        drop(commands);
        let _ = worker.join();
    }
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_submit(
    mut env: JNIEnv,
    _: JClass,
    command: JString,
) -> u8 {
    let result = (|| {
        let value: String = env.get_string(&command).ok()?.into();
        let command: Command = serde_json::from_str(&value).ok()?;
        let guard = ENGINE.get()?.lock().ok()?;
        guard.as_ref()?.commands.try_send(command).ok()
    })();
    u8::from(result.is_some())
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_poll(mut env: JNIEnv, _: JClass) -> jstring {
    let event = ENGINE
        .get()
        .and_then(|engine| engine.lock().ok()?.as_ref()?.events.try_recv().ok())
        .map(|event| event.to_string())
        .unwrap_or_default();
    output(&mut env, event)
}

fn sign_payload(key: &identity::Keypair, payload: &str) -> Option<String> {
    if payload.len() > 16_384 {
        return None;
    }
    let hex = |data: Vec<u8>| -> String { data.iter().map(|byte| format!("{byte:02x}")).collect() };
    Some(
        json!({
            "public": hex(key.public().encode_protobuf()),
            "signature": hex(key.sign(payload.as_bytes()).ok()?),
        })
        .to_string(),
    )
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_sign(
    mut env: JNIEnv,
    _: JClass,
    payload: JString,
) -> jstring {
    let result = (|| {
        let payload: String = env.get_string(&payload).ok()?.into();
        let guard = ENGINE.get()?.lock().ok()?;
        sign_payload(&guard.as_ref()?.key, &payload)
    })()
    .unwrap_or_default();
    output(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_signWith(
    mut env: JNIEnv,
    _: JClass,
    encoded: JString,
    payload: JString,
) -> jstring {
    let result = (|| {
        let encoded: String = env.get_string(&encoded).ok()?.into();
        let payload: String = env.get_string(&payload).ok()?.into();
        let root = identity_key(&encoded).ok()?;
        sign_payload(&root, &payload)
    })()
    .unwrap_or_default();
    output(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_p2p_NativeEngine_verify(
    mut env: JNIEnv,
    _: JClass,
    payload: JString,
    peer: JString,
    public: JString,
    signature: JString,
) -> u8 {
    let result = (|| {
        let payload: String = env.get_string(&payload).ok()?.into();
        let peer: String = env.get_string(&peer).ok()?.into();
        let public: String = env.get_string(&public).ok()?.into();
        let signature: String = env.get_string(&signature).ok()?.into();
        if payload.len() > 16_384 {
            return Some(false);
        }
        let key = identity::PublicKey::try_decode_protobuf(&hex_bytes(&public).ok()?).ok()?;
        Some(
            key.to_peer_id().to_string() == peer
                && key.verify(payload.as_bytes(), &hex_bytes(&signature).ok()?),
        )
    })()
    .unwrap_or(false);
    u8::from(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_grant_is_bound_to_recovery_key_and_device() {
        let root = identity::Keypair::generate_ed25519();
        let old = identity::Keypair::generate_ed25519();
        let replacement = identity::Keypair::generate_ed25519();
        let grant = |device: &identity::Keypair, revision: u64| {
            json!({
                "purpose": "p2p-device-v1",
                "account": root.public().to_peer_id().to_string(),
                "device": device.public().to_peer_id().to_string(),
                "revision": revision
            })
            .to_string()
        };
        let original = grant(&old, 1);
        let current = grant(&replacement, 2);
        let signature: Value =
            serde_json::from_str(&sign_payload(&root, &current).unwrap()).unwrap();
        let signature = hex_bytes(signature["signature"].as_str().unwrap()).unwrap();
        assert!(root.public().verify(current.as_bytes(), &signature));
        assert!(!root.public().verify(original.as_bytes(), &signature));
        assert!(!old.public().verify(current.as_bytes(), &signature));
    }

    fn await_event(engine: &Engine, predicate: impl Fn(&Value) -> bool) -> Value {
        let end = Instant::now() + Duration::from_secs(10);
        while let Ok(event) = engine
            .events
            .recv_timeout(end.saturating_duration_since(Instant::now()))
        {
            if predicate(&event) {
                return event;
            }
        }
        panic!("expected event was not received");
    }

    fn loopback_route(engine: &Engine, peer: PeerId) -> String {
        let listen = await_event(engine, |event| {
            event["kind"] == "listen"
                && event["address"].as_str().is_some_and(|value| {
                    value.starts_with("/ip4/127.0.0.1/") && value.ends_with("/quic-v1")
                })
        });
        format!("{}/p2p/{peer}", listen["address"].as_str().unwrap())
    }

    #[test]
    fn rendezvous_moves_an_opaque_record_through_an_app_peer() {
        let bootstrap_key = identity::Keypair::generate_ed25519();
        let bootstrap_id = bootstrap_key.public().to_peer_id();
        let bootstrap = launch(bootstrap_key, 0, vec!["127.0.0.1".into()]).unwrap();
        let address = loopback_route(&bootstrap, bootstrap_id);

        let open_client = || {
            let key = identity::Keypair::generate_ed25519();
            let client = launch(key, 0, vec!["127.0.0.1".into()]).unwrap();
            assert!(client
                .commands
                .try_send(Command::Route {
                    peer: bootstrap_id.to_string(),
                    addresses: vec![address.clone()],
                })
                .is_ok());
            assert!(client
                .commands
                .try_send(Command::Seed {
                    peer: bootstrap_id.to_string(),
                    addresses: vec![address.clone()],
                })
                .is_ok());
            assert!(client
                .commands
                .try_send(Command::Send {
                    ticket: "connect".into(),
                    peer: bootstrap_id.to_string(),
                    payload: "probe".into(),
                })
                .is_ok());
            let arrived = await_event(&bootstrap, |event| event["kind"] == "incoming");
            assert!(bootstrap
                .commands
                .try_send(Command::Acknowledge {
                    ticket: arrived["ticket"].as_u64().unwrap(),
                    status: "ok".into(),
                })
                .is_ok());
            assert_eq!(
                await_event(&client, |event| event["ticket"] == "connect")["status"],
                "ok"
            );
            client
        };
        let writer = open_client();
        let reader = open_client();
        let key = "a".repeat(64);
        assert!(writer
            .commands
            .try_send(Command::Publish {
                key: key.clone(),
                value: "opaque".into()
            })
            .is_ok());
        std::thread::sleep(Duration::from_millis(350));
        assert!(reader
            .commands
            .try_send(Command::Lookup { key: key.clone() })
            .is_ok());
        let record = await_event(&reader, |event| event["kind"] == "rendezvous");
        assert_eq!(record["key"], key);
        assert_eq!(record["value"], "opaque");
    }

    #[test]
    fn exchanges_acknowledged_messages_directly_over_quic() {
        let key_a = identity::Keypair::generate_ed25519();
        let key_b = identity::Keypair::generate_ed25519();
        let peer_a = key_a.public().to_peer_id();
        let peer_b = key_b.public().to_peer_id();
        let a = launch(key_a, 0, vec!["127.0.0.1".into()]).unwrap();
        let b = launch(key_b, 0, vec!["127.0.0.1".into()]).unwrap();
        let direct_route = loopback_route(&b, peer_b);
        assert!(a
            .commands
            .try_send(Command::Route {
                peer: peer_b.to_string(),
                addresses: vec![direct_route],
            })
            .is_ok());

        let exchange = |from: &Engine, from_id: PeerId, to: &Engine, to_id: PeerId, text: &str| {
            assert!(from
                .commands
                .try_send(Command::Send {
                    ticket: text.into(),
                    peer: to_id.to_string(),
                    payload: text.into(),
                })
                .is_ok());
            let incoming = await_event(to, |event| event["kind"] == "incoming");
            assert_eq!(incoming["peer"], from_id.to_string());
            assert_eq!(incoming["payload"], text);
            assert!(to
                .commands
                .try_send(Command::Acknowledge {
                    ticket: incoming["ticket"].as_u64().unwrap(),
                    status: "ok".into(),
                })
                .is_ok());
            let result = await_event(from, |event| event["ticket"] == text);
            assert_eq!(result["status"], "ok");
        };

        exchange(&a, peer_a, &b, peer_b, "hello");
        exchange(&b, peer_b, &a, peer_a, "reply");
    }
}
