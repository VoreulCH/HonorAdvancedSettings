import sys
import zipfile

base_apk, dex_path, out_apk = sys.argv[1], sys.argv[2], sys.argv[3]

with open(dex_path, "rb") as f:
    dex = f.read()

with zipfile.ZipFile(base_apk, "r") as zin:
    with zipfile.ZipFile(out_apk, "w") as zout:
        for item in zin.infolist():
            if item.filename == "classes.dex":
                continue
            zout.writestr(item.filename, zin.read(item.filename),
                          compress_type=item.compress_type)
        zout.writestr("classes.dex", dex, compress_type=zipfile.ZIP_DEFLATED)

print("injected classes.dex ->", out_apk)
