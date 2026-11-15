// libultra_hands smoke test. Uses ONLY the public C API
// (xr_mercury_runtime_c_api.h + ultra_hands_ext.h); plain C11.
// SPDX-License-Identifier: BSL-1.0
//
// usage: ultra_hands_smoke <models_dir> <calib.json> [--frames N]
//                          [--stereo left.pgm right.pgm label]...
//
// Exit code 0 = all hard checks passed (no crash, API contracts hold, blank
// frames report no hands). Results on noise / supplied images are REPORTED,
// not asserted.
#define _POSIX_C_SOURCE 200809L
#include "xr_mercury_runtime_c_api.h"
#include "ultra_hands_ext.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define W 480
#define H 640
#define FRAME_DT_NS 33333333ull

static int g_fail = 0;
#define CHECK(cond, ...)                                                                                               \
	do {                                                                                                           \
		if (!(cond)) {                                                                                         \
			printf("CHECK FAILED: " __VA_ARGS__);                                                          \
			printf("\n");                                                                                  \
			g_fail++;                                                                                      \
		}                                                                                                      \
	} while (0)

static double
now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static uint32_t g_rng = 0x12345678u;
static uint32_t
rnd(void)
{
	g_rng ^= g_rng << 13;
	g_rng ^= g_rng >> 17;
	g_rng ^= g_rng << 5;
	return g_rng;
}

static int
cmp_d(const void *a, const void *b)
{
	double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}

#define MAXF 4096
struct stats
{
	double t[MAXF];
	int n;
	int frames_with_hands;
	int left_active, right_active;
	int errors;
};

static void
stats_print(const char *label, struct stats *s)
{
	static double sorted[MAXF];
	double sum = 0;
	for (int i = 0; i < s->n; i++) sum += s->t[i];
	memcpy(sorted, s->t, sizeof(double) * (size_t)s->n);
	qsort(sorted, (size_t)s->n, sizeof(double), cmp_d);
	printf("[%-28s] frames=%d errors=%d frames_with_hands=%d (left=%d right=%d)  "
	       "ms/frame: mean=%.2f median=%.2f p95=%.2f min=%.2f max=%.2f first=%.2f\n",
	       label, s->n, s->errors, s->frames_with_hands, s->left_active, s->right_active, sum / s->n,
	       sorted[s->n / 2], sorted[(int)(s->n * 0.95)], sorted[0], sorted[s->n - 1], s->t[0]);
}

static uint64_t g_ts = 1000000000ull;
static uint64_t g_seq = 0;

static int
run_one(void *ctx, const uint8_t *l, const uint8_t *r, struct stats *s, struct xr_mercury_frame_f32 *out)
{
	g_ts += FRAME_DT_NS;
	g_seq++;
	double t0 = now_ms();
	int rc = xr_mercury_runtime_process_gray8(ctx, l, W, H, W, r, W, H, W, g_ts, g_seq, out);
	double t1 = now_ms();
	if (s->n < MAXF) s->t[s->n++] = t1 - t0;
	if (rc != 0) {
		s->errors++;
		printf("process rc=%d err=%s\n", rc, xr_mercury_runtime_last_error(ctx));
		return rc;
	}
	CHECK(out->size_bytes == sizeof(*out), "frame size_bytes %u != %zu", out->size_bytes, sizeof(*out));
	CHECK(out->sequence == g_seq && out->source_timestamp_ns == g_ts && out->timestamp_ns == g_ts,
	      "sequence/timestamps not echoed");
	CHECK(out->left.handedness == 1 && out->right.handedness == 2, "handedness fields");
	CHECK(out->left.joint_count == 21 && out->right.joint_count == 21, "joint_count");
	CHECK(out->hand_count == (uint32_t)((out->left.status == 1) + (out->right.status == 1)), "hand_count vs status");
	if (out->hand_count > 0) s->frames_with_hands++;
	if (out->left.status == 1) s->left_active++;
	if (out->right.status == 1) s->right_active++;
	return 0;
}

static void
print_hand(void *ctx, const char *name, const struct xr_mercury_hand_side_f32 *h, int hand_idx)
{
	printf("    %s: palm=(%.3f %.3f %.3f) wrist=(%.3f %.3f %.3f) index_tip=(%.3f %.3f %.3f) [m, cam0 frame]\n", name,
	       h->palm_px, h->palm_py, h->palm_pz, h->wrist_px, h->wrist_py, h->wrist_pz, h->joints[8].px,
	       h->joints[8].py, h->joints[8].pz);
	struct ultra_hands_joint_pose j26[ULTRA_HANDS_JOINT26_COUNT];
	int rc = ultra_hands_get_joints26(ctx, hand_idx, j26);
	CHECK(rc == 1, "joints26 should be active (rc=%d)", rc);
	// 21-joint struct vs 26-joint accessor must agree: 21[0]=26[1] wrist, 21[8]=26[10] index tip, palm=26[0].
	CHECK(fabsf(j26[1].px - h->joints[0].px) < 1e-6f && fabsf(j26[10].pz - h->joints[8].pz) < 1e-6f &&
	          fabsf(j26[0].py - h->palm_py) < 1e-6f,
	      "21-joint and 26-joint outputs disagree");
	float d = sqrtf(powf(j26[12].px - j26[1].px, 2) + powf(j26[12].py - j26[1].py, 2) +
	                powf(j26[12].pz - j26[1].pz, 2));
	printf("    %s: |wrist->middle_proximal| = %.3f m; index_metacarpal (26-set only)=(%.3f %.3f %.3f); "
	       "joint flags=%u\n",
	       name, d, j26[6].px, j26[6].py, j26[6].pz, h->joints[0].flags);
}

static uint8_t *
read_pgm(const char *path)
{
	FILE *f = fopen(path, "rb");
	if (!f) return NULL;
	int w = 0, h = 0, maxv = 0;
	if (fscanf(f, "P5 %d %d %d", &w, &h, &maxv) != 3 || w != W || h != H || maxv != 255) {
		fclose(f);
		return NULL;
	}
	fgetc(f);
	uint8_t *buf = malloc(W * H);
	if (fread(buf, 1, W * H, f) != W * H) {
		free(buf);
		buf = NULL;
	}
	fclose(f);
	return buf;
}

static void *
create(const char *models, const char *calib)
{
	struct xr_mercury_create_info info;
	memset(&info, 0, sizeof(info));
	info.abi_version = XR_MERCURY_RUNTIME_ABI_VERSION;
	info.size_bytes = sizeof(info);
	info.models_dir = models;
	info.calib_json = calib;
	// XR Gate defaults for XREAL Air 2 Ultra: upright 480x640 input, no swap, no boundary circle.
	info.orientation0 = 0;
	info.orientation1 = 0;
	info.swap_cameras = 0;
	info.boundary_circle = 0;
	return xr_mercury_runtime_create(&info);
}

int
main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr, "usage: %s <models_dir> <calib.json> [--frames N] [--stereo l.pgm r.pgm label]...\n",
		        argv[0]);
		return 2;
	}
	const char *models = argv[1];
	const char *calib = argv[2];
	int frames = 120;
	for (int a = 3; a + 1 < argc; a++) {
		if (strcmp(argv[a], "--frames") == 0) frames = atoi(argv[a + 1]);
	}
	if (frames < 1 || frames > MAXF) frames = 120;
	setvbuf(stdout, NULL, _IOLBF, 0);

	printf("== libultra_hands smoke test ==\n");
	printf("version: %s\n", ultra_hands_version());
	printf("sizeof: create_info=%zu joint=%zu hand_side=%zu frame=%zu\n", sizeof(struct xr_mercury_create_info),
	       sizeof(struct xr_mercury_joint_f32), sizeof(struct xr_mercury_hand_side_f32),
	       sizeof(struct xr_mercury_frame_f32));
	printf("models_dir: %s\ncalib_json: %s\nframes per sequence: %d, input %dx%d gray8, dt=33.3 ms\n", models, calib,
	       frames, W, H);

	// --- 1. failure paths ------------------------------------------------------------------------------------
	void *bad = create("/nonexistent/models", calib);
	CHECK(bad == NULL, "create with bad models dir must fail");
	printf("create(bad models dir) -> NULL, ultra_hands_last_create_error: \"%s\"\n", ultra_hands_last_create_error());
	CHECK(ultra_hands_last_create_error()[0] != 0, "create error must be reported");
	bad = create(models, "/nonexistent/calib.json");
	CHECK(bad == NULL, "create with bad calib must fail");
	printf("create(bad calib)      -> NULL, ultra_hands_last_create_error: \"%s\"\n", ultra_hands_last_create_error());
	CHECK(xr_mercury_runtime_process_gray8(NULL, NULL, 0, 0, 0, NULL, 0, 0, 0, 0, 0, NULL) == -1, "NULL ctx rc");

	uint8_t *black = calloc(W * H, 1), *gray = malloc(W * H), *white = malloc(W * H);
	uint8_t *noise_l = malloc(W * H), *noise_r = malloc(W * H);
	memset(gray, 128, W * H);
	memset(white, 255, W * H);
	static struct xr_mercury_frame_f32 out;
	static struct stats s, flush;

	// --- 2. threading configurations, blank + noise frames ---------------------------------------------------
	const int cfgs[][2] = {{4, 1} /* upstream default */, {2, 1}, {4, 2}};
	for (size_t c = 0; c < sizeof(cfgs) / sizeof(cfgs[0]); c++) {
		ultra_hands_set_threading(cfgs[c][0], cfgs[c][1], 0);
		int wt, it, sp;
		ultra_hands_get_threading(&wt, &it, &sp);
		double t0 = now_ms();
		void *ctx = create(models, calib);
		double t1 = now_ms();
		printf("\n-- config worker_threads=%d ort_intra_op_threads=%d spinning=%d : create %s in %.1f ms\n", wt,
		       it, sp, ctx ? "OK" : "FAILED", t1 - t0);
		if (!ctx) {
			printf("create error: %s\n", ultra_hands_last_create_error());
			g_fail++;
			break;
		}
		CHECK(xr_mercury_runtime_last_error(ctx)[0] == 0, "fresh ctx last_error should be empty");

		memset(&s, 0, sizeof(s));
		for (int i = 0; i < frames; i++) run_one(ctx, black, black, &s, &out);
		stats_print("blank black", &s);
		CHECK(s.frames_with_hands == 0 && s.errors == 0, "black frames must report no hands");
		CHECK(out.tracking_status == 0 && out.confidence == 0.0f && out.flags == 0, "no-hands frame fields");
		struct ultra_hands_joint_pose j26[ULTRA_HANDS_JOINT26_COUNT];
		CHECK(ultra_hands_get_joints26(ctx, 0, j26) == 0 && ultra_hands_get_joints26(ctx, 1, j26) == 0,
		      "joints26 inactive on blank");

		memset(&s, 0, sizeof(s));
		for (int i = 0; i < frames; i++) run_one(ctx, gray, gray, &s, &out);
		stats_print("blank gray(128)", &s);
		CHECK(s.frames_with_hands == 0 && s.errors == 0, "gray frames must report no hands");

		memset(&s, 0, sizeof(s));
		for (int i = 0; i < frames; i++) run_one(ctx, white, white, &s, &out);
		stats_print("blank white", &s);
		CHECK(s.frames_with_hands == 0 && s.errors == 0, "white frames must report no hands");

		memset(&s, 0, sizeof(s));
		for (int i = 0; i < frames; i++) {
			for (int p = 0; p < W * H; p++) {
				noise_l[p] = (uint8_t)rnd();
				noise_r[p] = (uint8_t)rnd();
			}
			run_one(ctx, noise_l, noise_r, &s, &out);
		}
		stats_print("uniform noise (per-pixel)", &s);
		CHECK(s.errors == 0, "noise frames must not error");
		int noise_hands = s.frames_with_hands;

		memset(&s, 0, sizeof(s));
		for (int i = 0; i < frames; i++) {
			// blocky low-frequency noise: 16x16 blocks, independent per eye
			for (int by = 0; by < H; by += 16) {
				for (int bx = 0; bx < W; bx += 16) {
					uint8_t vl = (uint8_t)rnd(), vr = (uint8_t)rnd();
					for (int y = by; y < by + 16; y++) {
						memset(noise_l + y * W + bx, vl, 16);
						memset(noise_r + y * W + bx, vr, 16);
					}
				}
			}
			run_one(ctx, noise_l, noise_r, &s, &out);
		}
		stats_print("block noise (16x16)", &s);
		CHECK(s.errors == 0, "block-noise frames must not error");
		printf("   noise summary: frames reporting a hand: per-pixel=%d block=%d (reported, not asserted)\n",
		       noise_hands, s.frames_with_hands);

		// stride != width path
		uint8_t *padded = calloc((W + 32) * H, 1);
		int rc = xr_mercury_runtime_process_gray8(ctx, padded, W, H, W + 32, padded, W, H, W + 32,
		                                          g_ts += FRAME_DT_NS, ++g_seq, &out);
		CHECK(rc == 0 && out.hand_count == 0, "padded-stride frame");
		// bad input must be an error code, not a crash
		rc = xr_mercury_runtime_process_gray8(ctx, NULL, W, H, W, black, W, H, W, g_ts, g_seq, &out);
		CHECK(rc == -2, "NULL image must return -2 (got %d)", rc);
		printf("   bad-input rc=%d last_error=\"%s\"\n", rc, xr_mercury_runtime_last_error(ctx));
		free(padded);

		// --- 3. optional user-supplied stereo image pairs ------------------------------------------------
		for (int a = 3; a + 3 < argc; a++) {
			if (strcmp(argv[a], "--stereo") != 0) continue;
			const char *lp = argv[a + 1], *rp = argv[a + 2], *label = argv[a + 3];
			uint8_t *l = read_pgm(lp), *r = read_pgm(rp);
			if (!l || !r) {
				printf("could not read 480x640 P5 pgm pair %s %s\n", lp, rp);
				g_fail++;
			} else {
				// Let tracking state from the previous sequence expire first.
				memset(&flush, 0, sizeof(flush));
				for (int i = 0; i < 10; i++) run_one(ctx, black, black, &flush, &out);
				memset(&s, 0, sizeof(s));
				int first_hand_frame = -1;
				for (int i = 0; i < frames; i++) {
					run_one(ctx, l, r, &s, &out);
					if (out.hand_count > 0 && first_hand_frame < 0) first_hand_frame = i;
				}
				char lab[64];
				snprintf(lab, sizeof(lab), "img %.24s", label);
				stats_print(lab, &s);
				printf("   first frame with a hand: %d; last frame: hand_count=%u left.status=%u "
				       "right.status=%u\n",
				       first_hand_frame, out.hand_count, out.left.status, out.right.status);
				if (out.left.status == 1) print_hand(ctx, "left ", &out.left, 0);
				if (out.right.status == 1) print_hand(ctx, "right", &out.right, 1);
			}
			free(l);
			free(r);
			a += 3;
		}

		xr_mercury_runtime_destroy(ctx);
	}

	// --- 4. create/destroy churn ------------------------------------------------------------------------------
	ultra_hands_set_threading(4, 1, 0);
	for (int i = 0; i < 5; i++) {
		void *ctx = create(models, calib);
		CHECK(ctx != NULL, "re-create %d", i);
		if (ctx) {
			memset(&s, 0, sizeof(s));
			run_one(ctx, black, black, &s, &out);
			xr_mercury_runtime_destroy(ctx);
		}
	}
	xr_mercury_runtime_destroy(NULL);
	printf("\ncreate/destroy x5 OK\n");

	free(black);
	free(gray);
	free(white);
	free(noise_l);
	free(noise_r);
	printf("\nRESULT: %s (%d failed checks)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
	return g_fail == 0 ? 0 : 1;
}
