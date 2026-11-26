"""Build signed Whitecat APK(s) using Android SDK build-tools, platform 35 and JDK 17+."""
import argparse
import hashlib
import os
import secrets
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent

def main():
    opt=argparse.ArgumentParser()
    opt.add_argument("--test",action="store_true",help="also build the separate instrumentation APK")
    opt.add_argument("--test-only",action="store_true",help="build only the separate device checks")
    args=opt.parse_args()
    cache=Path(os.getenv("LOCALAPPDATA") or Path.home())/"WhitecatAndroidTools"
    sdk=Path(os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT") or cache)
    jar=sdk/"platforms/android-35/android.jar"
    if not jar.is_file(): jar=cache/"platform/android-35/android.jar"
    suffix=".exe" if os.name=="nt" else ""
    build=Path(os.getenv("WHITECAT_BUILD_TOOLS") or sdk/"build-tools/35.0.0")
    if not (build/("aapt2"+suffix)).is_file():build=next((cache/"build").glob("*/aapt2.exe")).parent
    java_home=os.getenv("JAVA_HOME")
    jbin=Path(java_home)/"bin" if java_home else None
    if not jbin and (cache/"jdk").is_dir(): jbin=next((cache/"jdk").glob("*/bin/java.exe")).parent
    def jt(name):return str(jbin/(name+suffix)) if jbin else name
    store=Path(os.getenv("WHITECAT_SIGNING_DIR") or cache/"signing")
    store.mkdir(parents=True,exist_ok=True)
    password=store/"password"
    if not password.exists():
        with os.fdopen(os.open(password,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),"w") as f:f.write(secrets.token_urlsafe(32))
    env={**os.environ,"WHITECAT_KS_PASSWORD":password.read_text().strip()}
    ks=store/"whitecat.p12"
    def run(*cmd):subprocess.run([str(x) for x in cmd],check=True,env=env)
    if not ks.exists():
        run(jt("keytool"),"-genkeypair","-keystore",ks,"-storetype","PKCS12","-alias","whitecat","-keyalg","RSA",
            "-keysize","3072","-validity","3650","-dname","CN=Whitecat Internal","-storepass:env","WHITECAT_KS_PASSWORD")
    zxing = ROOT/"vendor/zxing-core-3.5.3.jar"
    if hashlib.sha256(zxing.read_bytes()).hexdigest() != "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82":
        raise ValueError("ZXing 3.5.3 校验失败")
    def make(tmp,source,manifest,res,output,classpath=None):
        tmp.mkdir();classes=tmp/"classes";classes.mkdir();gen=tmp/"gen";gen.mkdir()
        raw=tmp/"base.apk"; dex=tmp/"dex";dex.mkdir()
        # Native Windows aapt2 resolves non-ASCII command-line paths inconsistently.
        local_manifest=tmp/"AndroidManifest.xml";shutil.copyfile(manifest,local_manifest);manifest=local_manifest
        if res:
            local_res=tmp/"res";shutil.copytree(res,local_res);res=local_res
        aapt=build/("aapt2"+suffix)
        link=[aapt,"link","-I",jar,"--manifest",manifest,"--java",gen,"-o",raw]
        if res:
            compiled=tmp/"resources.zip";run(aapt,"compile","--dir",res,"-o",compiled);link.append(compiled)
        run(*link)
        src=sorted(source.rglob("*.java"))+sorted(gen.rglob("*.java"))
        run(jt("javac"),"-encoding","UTF-8","--release","8","-classpath",os.pathsep.join(map(str,[jar,zxing]+([classpath] if classpath else []))),
            "-d",classes,*src)
        classjar=tmp/"classes.jar"
        with zipfile.ZipFile(classjar,"w") as z:
            for cls in classes.rglob("*.class"):z.write(cls,cls.relative_to(classes).as_posix())
        run(jt("java"),"-cp",build/"lib/d8.jar","com.android.tools.r8.D8","--release","--lib",jar,
            *(["--classpath",classpath] if classpath else []),"--min-api","26","--output",dex,classjar,*([zxing] if res else []))
        with zipfile.ZipFile(raw,"a",zipfile.ZIP_DEFLATED) as z:
            z.write(dex/"classes.dex","classes.dex")
            if res:z.write(ROOT/"vendor/ZXing-LICENSE.txt", "META-INF/ZXing-LICENSE.txt")
        aligned=tmp/"aligned.apk";signed=tmp/"signed.apk"
        run(build/("zipalign"+suffix),"-f","4",raw,aligned)
        signer=[jt("java"),"-jar",build/"lib/apksigner.jar"]
        run(*signer,"sign","--ks",ks,"--ks-key-alias","whitecat","--ks-pass","env:WHITECAT_KS_PASSWORD","--v4-signing-enabled","false","--out",signed,aligned)
        run(*signer,"verify","--verbose",signed)
        output.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(signed,output)
        print(output,flush=True)
        return classjar
    with tempfile.TemporaryDirectory(prefix="whitecat-apk-") as work:
        work=Path(work)
        classes=None
        if not args.test_only:classes=make(work/"app",ROOT/"src",ROOT/"AndroidManifest.xml",ROOT/"res",ROOT/"白猫.apk")
        if args.test or args.test_only:make(work/"test",ROOT/"test/src",ROOT/"test/AndroidManifest.xml",None,ROOT/"test/whitecat-test.apk",classes)

if __name__=="__main__":
    main()
