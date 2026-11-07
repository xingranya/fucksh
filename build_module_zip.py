import zipfile, os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kernel-module")
FILES = ["module.prop", "post-fs-data.sh", "service.sh", "action.sh", "uninstall.sh", "skip_mount"]

ver = "?"
for line in open(os.path.join(SRC, "module.prop"), encoding="utf-8"):
    if line.startswith("version="):
        ver = line.split("=", 1)[1].strip()
OUT = os.path.join(os.path.dirname(SRC), f"fucksh_kernel_{ver}.zip")

if os.path.exists(OUT):
    os.remove(OUT)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for name in FILES:
        p = os.path.join(SRC, name)
        data = open(p, "rb").read()
        assert b"\r\n" not in data, "CRLF in " + name
        zi = zipfile.ZipInfo(name)
        zi.external_attr = (0o755 << 16) | 0x8000  # 可执行权限
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, data)

print("built", OUT, os.path.getsize(OUT), "bytes")
with zipfile.ZipFile(OUT) as z:
    for i in z.infolist():
        print(f"  {i.filename:20s} {i.file_size:6d}  {oct(i.external_attr >> 16)}")
