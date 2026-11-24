// libultra_hands: threading knobs + create-error reporting.
// SPDX-License-Identifier: BSL-1.0
#include "ultra_hands_ext.h"
#include "ultra_hands_config.h"

#include "util/u_git_tag.h"

#include <atomic>
#include <cstdlib>
#include <string>

namespace {

std::atomic<int> g_worker_threads{0};
std::atomic<int> g_ort_intra{0};
std::atomic<int> g_ort_spin{-1};
thread_local std::string t_create_error;

int
clampi(int v, int lo, int hi)
{
	return v < lo ? lo : (v > hi ? hi : v);
}

int
env_int(const char *name, int fallback)
{
	const char *v = std::getenv(name);
	if (v == nullptr || v[0] == '\0') {
		return fallback;
	}
	char *end = nullptr;
	long r = std::strtol(v, &end, 10);
	return (end == v) ? fallback : (int)r;
}

} // namespace

extern "C" int
ultra_hands_cfg_worker_threads(void)
{
	int v = g_worker_threads.load();
	{
		const int forced = env_int("ULTRA_HANDS_FORCE_WORKERS", 0);      // for measuring: wins over what the app asks for
		if (forced > 0) {
			v = forced;
		}
	}
	if (v <= 0) {
		v = env_int("ULTRA_HANDS_WORKER_THREADS", 4);
	}
	return clampi(v, 2, 16);
}

extern "C" int
ultra_hands_cfg_ort_intra_op_threads(void)
{
	int v = g_ort_intra.load();
	{
		const int forced = env_int("ULTRA_HANDS_FORCE_ORT_THREADS", 0);
		if (forced > 0) {
			v = forced;
		}
	}
	if (v <= 0) {
		v = env_int("ULTRA_HANDS_ORT_INTRA_OP_THREADS", 1);
	}
	return clampi(v, 1, 8);
}

extern "C" int
ultra_hands_cfg_ort_allow_spinning(void)
{
	int v = g_ort_spin.load();
	if (v < 0) {
		v = env_int("ULTRA_HANDS_ORT_ALLOW_SPINNING", 0);
	}
	return v != 0;
}

extern "C" void
ultra_hands_cfg_set_create_error(const char *msg)
{
	t_create_error = msg ? msg : "";
}

extern "C" ULTRA_HANDS_EXPORT void
ultra_hands_set_threading(int worker_threads, int ort_intra_op_threads, int ort_allow_spinning)
{
	if (worker_threads > 0) {
		g_worker_threads.store(worker_threads);
	}
	if (ort_intra_op_threads > 0) {
		g_ort_intra.store(ort_intra_op_threads);
	}
	g_ort_spin.store(ort_allow_spinning != 0 ? 1 : 0);
}

extern "C" ULTRA_HANDS_EXPORT void
ultra_hands_get_threading(int *worker_threads, int *ort_intra_op_threads, int *ort_allow_spinning)
{
	if (worker_threads) {
		*worker_threads = ultra_hands_cfg_worker_threads();
	}
	if (ort_intra_op_threads) {
		*ort_intra_op_threads = ultra_hands_cfg_ort_intra_op_threads();
	}
	if (ort_allow_spinning) {
		*ort_allow_spinning = ultra_hands_cfg_ort_allow_spinning();
	}
}

extern "C" ULTRA_HANDS_EXPORT const char *
ultra_hands_last_create_error(void)
{
	return t_create_error.c_str();
}

extern "C" ULTRA_HANDS_EXPORT const char *
ultra_hands_version(void)
{
	static const std::string v = std::string("libultra_hands monado=") + u_git_tag;
	return v.c_str();
}
