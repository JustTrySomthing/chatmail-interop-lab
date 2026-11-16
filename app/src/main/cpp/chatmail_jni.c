#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct dc_accounts dc_accounts_t;
typedef struct dc_jsonrpc_instance_t dc_jsonrpc_instance_t;
extern dc_accounts_t *dc_accounts_new(const char *, int);
extern void dc_accounts_unref(dc_accounts_t *);
extern dc_jsonrpc_instance_t *dc_jsonrpc_init(dc_accounts_t *);
extern void dc_jsonrpc_unref(dc_jsonrpc_instance_t *);
extern char *dc_jsonrpc_blocking_call(dc_jsonrpc_instance_t *, const char *);
extern void dc_str_unref(char *);

static char *copy_input(JNIEnv *env, jbyteArray input) {
    jsize length = (*env)->GetArrayLength(env, input);
    char *buffer = malloc((size_t)length + 1);
    if (buffer == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)buffer);
    if ((*env)->ExceptionCheck(env)) {
        free(buffer);
        return NULL;
    }
    buffer[length] = '\0';
    return buffer;
}

JNIEXPORT jlong JNICALL Java_app_p2p_ChatmailNative_open(
    JNIEnv *env, jclass klass, jbyteArray directory
) {
    char *path = copy_input(env, directory);
    if (path == NULL) return 0;
    dc_accounts_t *accounts = dc_accounts_new(path, 1);
    free(path);
    if (accounts == NULL) return 0;
    dc_jsonrpc_instance_t *rpc = dc_jsonrpc_init(accounts);
    dc_accounts_unref(accounts);
    return (jlong)(intptr_t)rpc;
}

JNIEXPORT jbyteArray JNICALL Java_app_p2p_ChatmailNative_call(
    JNIEnv *env, jclass klass, jlong handle, jbyteArray input
) {
    char *request = copy_input(env, input);
    if (request == NULL) return NULL;
    char *response = dc_jsonrpc_blocking_call((dc_jsonrpc_instance_t *)(intptr_t)handle, request);
    free(request);
    if (response == NULL) return NULL;
    jsize length = (jsize)strlen(response);
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)response);
    }
    dc_str_unref(response);
    return result;
}

JNIEXPORT void JNICALL Java_app_p2p_ChatmailNative_close(
    JNIEnv *env, jclass klass, jlong handle
) {
    dc_jsonrpc_unref((dc_jsonrpc_instance_t *)(intptr_t)handle);
}
