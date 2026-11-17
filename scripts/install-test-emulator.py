#!/usr/bin/env python3
"""Install pinned official Linux emulator and Android 15 test image; verify archive checksums."""
from pathlib import Path
import urllib.request,zipfile,hashlib,concurrent.futures,os
sdk=Path(os.environ['ANDROID_HOME']).resolve()
sdk.mkdir(parents=True,exist_ok=True)
cache=Path(os.environ.get('RELAY_DOWNLOAD_CACHE',str(sdk/'download-cache')))
cache.mkdir(parents=True,exist_ok=True)
def install(item):
 name,url,sha,dest=item
 f=cache/(name+'.zip')
 urllib.request.urlretrieve(url,f)
 h=hashlib.sha1()
 with f.open('rb') as s:
  for b in iter(lambda:s.read(1024*1024),b''):h.update(b)
 assert h.hexdigest()==sha
 with zipfile.ZipFile(f) as z:
  z.extractall(dest)
  for i in z.infolist():
   mode=i.external_attr>>16
   if mode: (dest/i.filename).chmod(mode&0o777)
 print(name+' installed, checksum verified',flush=True)
with concurrent.futures.ThreadPoolExecutor() as pool:
 list(pool.map(install,[('emulator','https://dl.google.com/android/repository/emulator-linux_x64-16740729.zip','f41f9e31eb5ca64926603c03265c50bbc0ff4172',sdk),('android35-image','https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip','0103e6dab21290c4b9d16550a3ce99476f884eef',sdk/'system-images/android-35/google_apis')]))
