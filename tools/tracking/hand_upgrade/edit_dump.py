import os
d = r"C:\uvio\hb\monado\src\xrt\tracking\hand\mercury"
def edit(name, pairs, src=None):
    p = os.path.join(d, name)
    raw = open(p, encoding='utf-8', newline='').read(); crlf = '\r\n' in raw; s = raw.replace('\r\n', '\n')
    for a, b in pairs:
        assert s.count(a) == 1, (name, a[:80], s.count(a)); s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)

edit("ultra_prof.hpp", [
("} // namespace ultra_prof\n", r'''
/** ULTRA_HANDS_DUMP_DIR=dir: every ULTRA_HANDS_DUMP_EVERY-th (default 7) input of the models is appended to dir/<name>.bin (float32). */
inline void dump(const char *name, const float *data, int n)
{
	static const char *dir = std::getenv("ULTRA_HANDS_DUMP_DIR");
	if (dir == nullptr || dir[0] == '\0') {
		return;
	}
	static const int every = []() { const char *e = std::getenv("ULTRA_HANDS_DUMP_EVERY"); return e ? std::atoi(e) : 7; }();
	static std::atomic<int> k[8];
	int slot = name[0] == 'd' ? 0 : (name[2] == 'i' ? 1 : (name[2] == 'l' ? 2 : 3));
	if (k[slot].fetch_add(1) % (every > 0 ? every : 7) != 0) {
		return;
	}
	char path[512];
	snprintf(path, sizeof path, "%s/%s.bin", dir, name);
	static std::atomic_flag lock = ATOMIC_FLAG_INIT;
	while (lock.test_and_set(std::memory_order_acquire)) {
	}
	FILE *f = fopen(path, "ab");
	if (f) {
		fwrite(data, sizeof(float), (size_t)n, f);
		fclose(f);
	}
	lock.clear(std::memory_order_release);
}

} // namespace ultra_prof
'''),
])
edit("hg_model.cpp", [
("\t\tconst uint64_t _prof_t1 = ultra_prof::now();\n", "\t\tultra_prof::dump(\"det\", state->wraps[0].data, kDetectionInputSize * kDetectionInputSize);\n\t\tconst uint64_t _prof_t1 = ultra_prof::now();\n"),
("\t\tconst uint64_t _prof_k1 = ultra_prof::now();\n", "\t\tultra_prof::dump(\"kpimg\", state->wraps[0].data, 128 * 128); ultra_prof::dump(\"kplast\", state->wraps[1].data, 42); ultra_prof::dump(\"kpuse\", state->wraps[2].data, 1);\n\t\tconst uint64_t _prof_k1 = ultra_prof::now();\n"),
])
print('ok')
