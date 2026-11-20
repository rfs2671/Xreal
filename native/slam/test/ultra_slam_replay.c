// A recording's stereo pairs and the position estimator's poses through libultra_slam.so on the phone: make a map
// from one stretch, save it, open it again as a new session whose world begins somewhere else, and see whether and
// how well the head is found in the saved map.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// usage: ultra_slam_replay <map pairs> <map poses> <find pairs> <find poses> <rig.txt> <map.db> <out.csv>
//                          <map from s> <map to s> <find from s> <find to s> [rate hz] [params file]
//   pairs   tools/tracking/hand_pairs_export.py: "UXPAIRS1", count, then (int64 ns, left 480x640, right 480x640)
//   poses   tools/tracking/slam_export.py: "t_s px py pz qx qy qz qw trusted" a line
//   "-" for the map pairs: the map file is used as it is (find only)
//
// The same recording for both: the finder's poses are the estimator's moved by a fixed turn and shift it is not told
// (a new session begins wherever the head happens to be), and a pose found in the map is compared with the
// estimator's own pose in the first stretch's world. That reference drifts as the estimator drifts between the two
// stretches: the error printed is the relocalisation error plus that drift.
// Two recordings: there is no reference; what is printed is whether the head was found, and how steady the map's
// idea of the new session's world is (the spread of the corrections).
#define _POSIX_C_SOURCE 200809L
#include "ultra_slam.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define W 480
#define H 640

typedef struct {
	double t, v[7];
	int ok;
} pose_t;

static void qmul(const double *a, const double *b, double *o) { // xyzw
	o[0] = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1];
	o[1] = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0];
	o[2] = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3];
	o[3] = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2];
}
static void qrot(const double *q, const double *v, double *o) {
	const double x = q[0], y = q[1], z = q[2], w = q[3];
	const double tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]);
	o[0] = v[0] + w * tx + (y * tz - z * ty);
	o[1] = v[1] + w * ty + (z * tx - x * tz);
	o[2] = v[2] + w * tz + (x * ty - y * tx);
}
static void compose(const double *a, const double *b, double *o) { // a * b, each p xyz q xyzw
	double r[3];
	qrot(a + 3, b, r);
	o[0] = a[0] + r[0], o[1] = a[1] + r[1], o[2] = a[2] + r[2];
	qmul(a + 3, b + 3, o + 3);
}
static int cmp(const void *a, const void *b) {
	const double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}
static pose_t *read_poses(const char *path, int *n) {
	FILE *f = fopen(path, "r");
	if (!f) return perror(path), NULL;
	int cap = 4096;
	pose_t *p = malloc(sizeof(pose_t) * cap);
	double v[9];
	*n = 0;
	while (fscanf(f, "%lf %lf %lf %lf %lf %lf %lf %lf %lf", v, v + 1, v + 2, v + 3, v + 4, v + 5, v + 6, v + 7, v + 8) == 9) {
		if (*n == cap) p = realloc(p, sizeof(pose_t) * (cap *= 2));
		p[*n].t = v[0], p[*n].ok = (int)v[8];
		memcpy(p[*n].v, v + 1, sizeof p[*n].v);
		(*n)++;
	}
	fclose(f);
	return p;
}
static char *slurp(const char *path) {
	FILE *f = fopen(path, "rb");
	if (!f) return NULL;
	fseek(f, 0, SEEK_END);
	long n = ftell(f);
	fseek(f, 0, SEEK_SET);
	char *s = malloc(n + 1);
	n = (long)fread(s, 1, n, f);
	s[n] = 0;
	fclose(f);
	return s;
}

int main(int argc, char **argv) {
	if (argc < 12) {
		fprintf(stderr, "usage: %s map.pairs map.poses find.pairs find.poses rig.txt map.db out.csv map_from map_to find_from find_to [rate] [params]\n", argv[0]);
		return 2;
	}
	const double span[2][2] = {{atof(argv[8]), atof(argv[9])}, {atof(argv[10]), atof(argv[11])}};
	const double rate = argc > 12 ? atof(argv[12]) : 2.0;
	char *rig = slurp(argv[5]), *params = argc > 13 ? slurp(argv[13]) : NULL;
	if (!rig) return perror("rig"), 1;
	const int same = strcmp(argv[1], argv[3]) == 0;
	const double yaw = 40.0 * M_PI / 180.0;
	const double G[7] = {1.5, -0.7, 0.3, 0, 0, sin(yaw / 2), cos(yaw / 2)}; // the new session's world in the old one's
	FILE *out = fopen(argv[7], "w");
	if (!out) return perror("out"), 1;
	fprintf(out, "phase,t,results,place,inliers,places,ms,has,cx,cy,cz,cqx,cqy,cqz,cqw,err_m\n");
	printf("%s\n", uslam_version());
	static uint8_t l[W * H], r[W * H];

	// UX_EXTEND="from to": between making and finding, the map is opened again and added to from that stretch of the
	// first recording, as a session of its own whose world is turned and shifted (another unknown offset)
	double exts[6][2];
	int next = 0;
	if (getenv("UX_EXTEND")) {
		const char *q = getenv("UX_EXTEND");
		while (next < 6 && sscanf(q, "%lf %lf", &exts[next][0], &exts[next][1]) == 2) {
			next++;
			q = strchr(q, ';');
			if (!q) break;
			q++;
		}
	}
	for (int step = 0; step < 2 + next; step++) {
		const int ex = step >= 1 && step <= next, phase = step == 1 + next ? 1 : 0;
		double ext[2] = {0, 0};
		if (ex) ext[0] = exts[step - 1][0], ext[1] = exts[step - 1][1];
		// each adding session has an offset of its own
		const double yaw2 = (-65.0 + 37.0 * (step - 1)) * M_PI / 180.0;
		const double G2[7] = {-0.8 + 0.5 * (step - 1), 2.1 - 0.7 * (step - 1), -0.2, 0, 0, sin(yaw2 / 2), cos(yaw2 / 2)};
		if (step == 0 && (strcmp(argv[1], "-") == 0 || getenv("UX_SKIP_MAP"))) continue;      // UX_SKIP_MAP: the map file is used as it is
		int np = 0;
		pose_t *poses = read_poses(argv[2 + 2 * phase], &np);
		if (!poses) return 1;
		uslam_t *h = uslam_create(rig, argv[6], ex ? USLAM_EXTEND : phase == 0 ? USLAM_MAP : USLAM_FIND, params);
		const double from = ex ? ext[0] : span[phase][0], to = ex ? ext[1] : span[phase][1];
		if (!h) return fprintf(stderr, "create: %s\n", uslam_last_error()), 1;
		FILE *pf = fopen(argv[1 + 2 * phase], "rb");
		if (!pf) return perror("pairs"), 1;
		char magic[8];
		int count = 0;
		if (fread(magic, 1, 8, pf) != 8 || fread(&count, 4, 1, pf) != 1) return fprintf(stderr, "pairs: bad header\n"), 1;
		double last = -1e9, first_found = -1, *ms = malloc(sizeof(double) * (count + 1)), *err = malloc(sizeof(double) * (count + 1)), (*cs)[3] = malloc(sizeof(double[3]) * (count + 1)), *cyaw = malloc(sizeof(double) * (count + 1));
		int n = 0, ne = 0, nc = 0, pi = 0;
		long long found_before = 0;
		uslam_state st;
		for (int k = 0; k < count; k++) {
			long long tns;
			if (fread(&tns, 8, 1, pf) != 1) break;
			const double t = tns * 1e-9;
			if (t >= to) break;
			if (t < from || t - last < 1.0 / rate) {
				fseek(pf, 2L * W * H, SEEK_CUR);
				continue;
			}
			if (fread(l, 1, sizeof l, pf) != sizeof l || fread(r, 1, sizeof r, pf) != sizeof r) break;
			while (pi + 1 < np && fabs(poses[pi + 1].t - t) <= fabs(poses[pi].t - t)) pi++;
			if (np == 0 || fabs(poses[pi].t - t) > 0.02 || !poses[pi].ok) continue; // no trusted pose for this pair
			last = t;
			double odom[7];
			if (ex) compose(G2, poses[pi].v, odom);
			else if (phase == 1 && same) compose(G, poses[pi].v, odom);
			else memcpy(odom, poses[pi].v, sizeof odom);
			uslam_feed(h, tns, l, r, W, H, odom);
			uslam_wait_idle(h);
			uslam_get_state(h, &st);
			const int hit = st.found > found_before;
			found_before = st.found;
			ms[n++] = st.last_ms;
			double e = -1;
			if ((phase == 1 || ex) && st.has_corr) {
				double in[7];
				compose(st.corr, odom, in);
				if (same || ex) {
					e = sqrt((in[0] - poses[pi].v[0]) * (in[0] - poses[pi].v[0]) + (in[1] - poses[pi].v[1]) * (in[1] - poses[pi].v[1]) + (in[2] - poses[pi].v[2]) * (in[2] - poses[pi].v[2]));
					err[ne++] = e;
				}
				if (hit) {
					memcpy(cs[nc], st.corr, sizeof(double[3]));
					cyaw[nc++] = 2 * atan2(st.corr[5], st.corr[6]) * 180 / M_PI;
				}
				if (first_found < 0) first_found = t;
			}
			fprintf(out, "%s,%.3f,%lld,%d,%d,%d,%.1f,%d,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f,%.5f,%.4f\n", ex ? "extend" : phase == 0 ? "map" : "find", t, (long long)st.results, hit ? st.last_place : 0, hit ? st.inliers : 0,
			        st.places, st.last_ms, st.has_corr, st.corr[0], st.corr[1], st.corr[2], st.corr[3], st.corr[4], st.corr[5], st.corr[6], e);
		}
		fclose(pf);
		uslam_get_state(h, &st);
		qsort(ms, n, sizeof(double), cmp);
		printf("%s %.0f to %.0f s: %d pairs given, %d places in the map, %lld pairs matched to it; a pair takes median %.0f ms, 90th %.0f, worst %.0f\n", ex ? "extend" : phase == 0 ? "map" : "find",
		       from, to, n, st.places, (long long)st.found, n ? ms[n / 2] : 0, n ? ms[n * 9 / 10] : 0, n ? ms[n - 1] : 0);
		if (ex) printf("extend: %s\n", !st.linked ? "the session never saw the map: nothing is written" : !st.added ? "nothing was added: all of it was in the map" : "places were added");
		if (phase == 1 || ex) {
			if (first_found < 0) printf("find: the head was never found in the saved map\n");
			else {
				printf("find: first found %.1f s into the new session\n", first_found - from);
				if (ne) {
					qsort(err, ne, sizeof(double), cmp);
					printf("find: position against the estimator's own, over %d pairs: median %.1f cm, 90th %.1f cm, worst %.1f cm\n", ne, 100 * err[ne / 2], 100 * err[ne * 9 / 10], 100 * err[ne - 1]);
				}
				if (nc > 1) {
					// how far the corrections lie from their middle one: with no drift they would all be the same
					double m[3], sp[4096];
					for (int a = 0; a < 3; a++) {
						double *col = malloc(sizeof(double) * nc);
						for (int i = 0; i < nc; i++) col[i] = cs[i][a];
						qsort(col, nc, sizeof(double), cmp);
						m[a] = col[nc / 2];
						free(col);
					}
					int k2 = nc < 4096 ? nc : 4096;
					for (int i = 0; i < k2; i++) sp[i] = sqrt((cs[i][0] - m[0]) * (cs[i][0] - m[0]) + (cs[i][1] - m[1]) * (cs[i][1] - m[1]) + (cs[i][2] - m[2]) * (cs[i][2] - m[2]));
					qsort(sp, k2, sizeof(double), cmp);
					qsort(cyaw, nc, sizeof(double), cmp);
					printf("find: %d corrections; from their middle one: median %.1f cm, 90th %.1f cm, worst %.1f cm; heading from %.1f to %.1f degrees\n", nc, 100 * sp[k2 / 2], 100 * sp[k2 * 9 / 10],
					       100 * sp[k2 - 1], cyaw[0], cyaw[nc - 1]);
				}
			}
		}
		uslam_destroy(h, 1);
		free(poses), free(ms), free(err), free(cs), free(cyaw);
		fflush(stdout);
	}
	fclose(out);
	return 0;
}
