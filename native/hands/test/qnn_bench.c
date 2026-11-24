// How long one run of a hand model takes on the Hexagon (HTP) through ONNX Runtime's QNN execution provider, against the CPU.
// Needs the QNN HTP libraries of Qualcomm's AI Engine Direct SDK next to it on the phone (see docs/HAND_TRACKING.md, 24 November 2026).
// SPDX-License-Identifier: BSL-1.0
//
// usage: qnn_bench <model.onnx> <qnn|cpu> [runs] [fp16 0|1] [perf burst|sustained_high_performance|default] [backend path]
// QNN_PROFILE=1 in the environment asks the provider for a basic profile. Prints the median, the 90th percentile and the first run, in ms,
// a checksum of the first output, and, for qnn, whether the provider took the graph (the number of nodes it was given, from the session's own log).
#define _POSIX_C_SOURCE 200809L
#include "onnxruntime_c_api.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static const OrtApi *A;
#define CHECK(expr)                                                                                                    \
	do {                                                                                                           \
		OrtStatus *st_ = (expr);                                                                               \
		if (st_ != NULL) {                                                                                     \
			fprintf(stderr, "ort: %s\n", A->GetErrorMessage(st_));                                         \
			return 1;                                                                                      \
		}                                                                                                      \
	} while (0)

static double now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}
static int cmp_d(const void *a, const void *b)
{
	double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}

int main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr, "usage: %s <model.onnx> <qnn|cpu> [runs] [fp16] [perf] [backend path]\n", argv[0]);
		return 2;
	}
	A = OrtGetApiBase()->GetApi(ORT_API_VERSION);
	const int runs = argc > 3 ? atoi(argv[3]) : 200;
	const char *fp16 = argc > 4 ? argv[4] : "0", *perf = argc > 5 ? argv[5] : "burst", *backend = argc > 6 ? argv[6] : "libQnnHtp.so";
	OrtEnv *env; CHECK(A->CreateEnv(getenv("QNN_LOG") ? ORT_LOGGING_LEVEL_VERBOSE : ORT_LOGGING_LEVEL_WARNING, "qnn_bench", &env));
	OrtSessionOptions *so; CHECK(A->CreateSessionOptions(&so));
	CHECK(A->SetIntraOpNumThreads(so, 1));
	if (strcmp(argv[2], "qnn") == 0) {
		const char *keys[6]; const char *vals[6]; int n = 0;
		keys[n] = "backend_path"; vals[n++] = backend;
		keys[n] = "htp_performance_mode"; vals[n++] = perf;
		keys[n] = "enable_htp_fp16_precision"; vals[n++] = fp16;
		keys[n] = "htp_graph_finalization_optimization_mode"; vals[n++] = "3";
		if (getenv("QNN_PROFILE")) { keys[n] = "profiling_level"; vals[n++] = "basic"; }
		CHECK(A->SessionOptionsAppendExecutionProvider(so, "QNN", keys, vals, n));
		printf("qnn: backend %s perf %s fp16 %s\n", backend, perf, fp16);
	}
	OrtSession *s; double t0 = now_ms(); CHECK(A->CreateSession(env, argv[1], so, &s)); double load = now_ms() - t0;
	OrtAllocator *al; CHECK(A->GetAllocatorWithDefaultOptions(&al));
	size_t nin, nout; CHECK(A->SessionGetInputCount(s, &nin)); CHECK(A->SessionGetOutputCount(s, &nout));
	OrtMemoryInfo *mi; CHECK(A->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mi));
	char *iname[4], *oname[8]; OrtValue *in[4] = {0};
	for (size_t i = 0; i < nin; i++) {
		CHECK(A->SessionGetInputName(s, i, al, &iname[i]));
		OrtTypeInfo *ti; CHECK(A->SessionGetInputTypeInfo(s, i, &ti));
		const OrtTensorTypeAndShapeInfo *ts; CHECK(A->CastTypeInfoToTensorInfo(ti, &ts));
		size_t nd; CHECK(A->GetDimensionsCount(ts, &nd)); int64_t dims[8]; CHECK(A->GetDimensions(ts, dims, nd));
		size_t cnt = 1; for (size_t d = 0; d < nd; d++) { if (dims[d] <= 0) dims[d] = 1; cnt *= (size_t)dims[d]; }
		float *buf = (float *)malloc(cnt * sizeof(float)); for (size_t k = 0; k < cnt; k++) buf[k] = 0.5f + 0.4f * sinf((float)k * 0.37f);
		CHECK(A->CreateTensorWithDataAsOrtValue(mi, buf, cnt * sizeof(float), dims, nd, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in[i]));
	}
	for (size_t i = 0; i < nout; i++) CHECK(A->SessionGetOutputName(s, i, al, &oname[i]));
	double *t = (double *)malloc(sizeof(double) * runs); double sum = 0;
	for (int r = 0; r < runs; r++) {
		OrtValue *out[8] = {0}; double a = now_ms();
		CHECK(A->Run(s, NULL, (const char *const *)iname, (const OrtValue *const *)in, nin, (const char *const *)oname, nout, out));
		t[r] = now_ms() - a;
		if (r == 0) { float *p; CHECK(A->GetTensorMutableData(out[0], (void **)&p)); OrtTensorTypeAndShapeInfo *ti; CHECK(A->GetTensorTypeAndShape(out[0], &ti)); size_t c; CHECK(A->GetTensorShapeElementCount(ti, &c)); for (size_t k = 0; k < c; k++) sum += p[k]; }
		for (size_t i = 0; i < nout; i++) A->ReleaseValue(out[i]);
	}
	double first = t[0]; qsort(t, runs, sizeof(double), cmp_d);
	printf("%s %s: median %.2f ms, 90th %.2f, first %.1f, load %.0f ms, output sum %.5f\n", argv[2], argv[1], t[runs / 2], t[(int)(runs * 0.9)], first, load, sum);
	return 0;
}
