#!/usr/bin/env python3
"""Host replacement for rtabmap-res_tool (utilite/resource_generator/main.cpp).
No host C++ compiler is needed: writes <NAME>.h holding the file as an upper-case hex string
in 1024-byte chunks, byte-for-byte the format the C++ tool produces (uBytes2Hex)."""
import os, sys
def main(a):
    ns, target, k = "", os.getcwd(), 0
    while k < len(a) - 1:
        if a[k] == "-n": ns = a[k + 1]; k += 2
        elif a[k] == "-p": target = a[k + 1]; k += 2
        else: break
    for path in a[k:]:
        var = "".join(c if c.isascii() and c.isalnum() else "_" for c in os.path.basename(path))
        out = os.path.join(target, var + ".h")
        var = "".join(chr(ord(c) - 32) if "a" <= c <= "z" else c for c in var)
        data = open(path, "rb").read()
        with open(out, "w", newline="\n") as f:
            f.write("/*This is a generated file...*/\n\n#ifndef %s_H\n#define %s_H\n\n" % (var, var))
            if ns: f.write("namespace %s\n{\n\n" % ns)
            f.write("static const char * %s = " % var)
            if not data: f.write('""')
            for i in range(0, len(data), 1024):
                f.write('\n   "' + data[i:i + 1024].hex().upper() + '"')
            f.write(";\n\n")
            if ns: f.write("}\n\n")
            f.write("#endif //%s_H\n\n" % var)
        print("res_tool.py: %s -> %s (%d bytes)" % (path, out, len(data)))
if __name__ == "__main__":
    main(sys.argv[1:])
