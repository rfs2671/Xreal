import os
d = r"C:\uvio\hb\monado\src\xrt\tracking\hand\mercury"
BS = chr(92)
def load(name):
    raw = open(os.path.join(d, name), encoding='utf-8', newline='').read()
    return ('\r\n' in raw), raw.replace('\r\n', '\n')
def save(name, crlf, s):
    open(os.path.join(d, name), 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)

crlf, s = load("hg_model.cpp")
def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:80], s.count(a)); s = s.replace(a, b)
nul = "'" + BS + "0'"
rep("static bool\nultra_recall_on()\n{\n\tstatic const bool on = []() {\n\t\tconst char *e = std::getenv(\"ULTRA_HANDS_RECALL\");\n\t\treturn e != nullptr && e[0] != " + nul + " && e[0] != '0';\n\t}();\n\treturn on;\n}",
"""static int
ultra_recall_mode()
{
	static const int mode = []() {
		const char *e = std::getenv("ULTRA_HANDS_RECALL");
		return (e != nullptr && e[0] != 0) ? (e[0] - '0') : 0;
	}();
	return mode;
}
// ULTRA_HANDS_TRUST_TILES=1: a tile's find (confidence >= 0.5) begins a candidate without the other camera's word or a second look.
// The keypoint network and the settled-fit gate still decide whether it is ever shown.
static bool
ultra_trust_tiles()
{
	static const bool on = std::getenv("ULTRA_HANDS_TRUST_TILES") != nullptr;
	return on;
}""")
rep("if (sched == 1 && counter - T.mem[h].lost_at <= 45 && ultra_recall_on()) {",
    "if (sched == 1 && ((ultra_recall_mode() == 1 && counter - T.mem[h].lost_at <= 45) || ultra_trust_tiles())) {")
rep("if (hypotf(px - ex, py - ey) < 80.0f || hypotf(px - m.last.x, py - m.last.y) < 80.0f) {",
    "if (ultra_trust_tiles() || hypotf(px - ex, py - ey) < 80.0f || hypotf(px - m.last.x, py - m.last.y) < 80.0f) {")
save("hg_model.cpp", crlf, s)

crlf, s = load("hg_sync.cpp")
old = "return e != nullptr && e[0] != " + nul + " && e[0] != '0';"
print("sync count", s.count(old))
s = s.replace(old, "return e != nullptr && e[0] == '1';")
save("hg_sync.cpp", crlf, s)
print("ok")
