// libultra_hands: runs a file of recorded stereo pairs through the tracker and prints one line a pair.
// For finding out where a hand is lost, on the phone itself (adb shell), with Mercury's own debug logs switched on
// through the environment. Uses only the public C API. SPDX-License-Identifier: BSL-1.0
//
// usage: ultra_hands_replay <models_dir> <calib.json> <pairs file> [first_pair [pair_count]]
//   pairs file: tools/tracking/hand_pairs_export.py ("UXPAIRS1", int32 count, then int64 ns + two 480x640 pictures a pair)
// output, a line a pair: t_s,mask,ms, then for the left and for the right hand: palm x,y,z (metres, the left
//   camera's frame; empty when not tracked) and the thumb-tip to index-tip distance
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

static double
now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

int
main(int argc, char **argv)
{
	if (argc < 4) {
		fprintf(stderr, "usage: %s <models_dir> <calib.json> <pairs file> [first [count]]\n", argv[0]);
		return 2;
	}
	FILE *f = fopen(argv[3], "rb");
	if (!f) {
		perror("pairs file");
		return 2;
	}
	char magic[8];
	int32_t count = 0;
	if (fread(magic, 1, 8, f) != 8 || memcmp(magic, "UXPAIRS1", 8) != 0 || fread(&count, 4, 1, f) != 1) {
		fprintf(stderr, "not a pairs file\n");
		return 2;
	}
	long first = argc > 4 ? atol(argv[4]) : 0;
	long want = argc > 5 ? atol(argv[5]) : count;

	struct xr_mercury_create_info info;
	memset(&info, 0, sizeof(info));
	info.abi_version = XR_MERCURY_RUNTIME_ABI_VERSION;
	info.size_bytes = sizeof(info);
	info.models_dir = argv[1];
	info.calib_json = argv[2];
	void *ctx = xr_mercury_runtime_create(&info);
	if (!ctx) {
		fprintf(stderr, "create failed: %s\n", ultra_hands_last_create_error());
		return 1;
	}
	fprintf(stderr, "%s, %d pairs in the file\n", ultra_hands_version(), (int)count);

	uint8_t *l = malloc(W * H), *r = malloc(W * H);
	struct xr_mercury_frame_f32 out;
	printf("t,mask,ms,l_x,l_y,l_z,l_gap,r_x,r_y,r_z,r_gap\n");
	long done = 0;
	for (long i = 0; i < count && done < want; i++) {
		int64_t t = 0;
		if (fread(&t, 8, 1, f) != 1 || fread(l, 1, W * H, f) != W * H || fread(r, 1, W * H, f) != W * H) {
			break;
		}
		if (i < first) {
			continue;
		}
		memset(&out, 0, sizeof(out));
		double t0 = now_ms();
		int rc = xr_mercury_runtime_process_gray8(ctx, l, W, H, W, r, W, H, W, (uint64_t)t + 1000000000ull, (uint64_t)i, &out);
		double ms = now_ms() - t0;
		if (rc != 0) {
			fprintf(stderr, "pair %ld: error %d %s\n", i, rc, xr_mercury_runtime_last_error(ctx));
			continue;
		}
		int mask = (out.left.status ? 1 : 0) | (out.right.status ? 2 : 0);
		printf("%.3f,%d,%.1f", t / 1e9, mask, ms);
		const struct xr_mercury_hand_side_f32 *hands[2] = {&out.left, &out.right};
		for (int h = 0; h < 2; h++) {
			const struct xr_mercury_hand_side_f32 *s = hands[h];
			if (!s->status) {
				printf(",,,,");
				continue;
			}
			float dx = s->joints[4].px - s->joints[8].px, dy = s->joints[4].py - s->joints[8].py, dz = s->joints[4].pz - s->joints[8].pz;
			printf(",%.3f,%.3f,%.3f,%.3f", s->palm_px, s->palm_py, s->palm_pz, sqrtf(dx * dx + dy * dy + dz * dz));
		}
		printf("\n");
		done++;
	}
	xr_mercury_runtime_destroy(ctx);
	free(l);
	free(r);
	fclose(f);
	return 0;
}
