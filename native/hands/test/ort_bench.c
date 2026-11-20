// How long one run of a hand model takes on the phone, by execution provider and thread count. ONNX Runtime C API only.
// SPDX-License-Identifier: BSL-1.0
//
// usage: ort_bench <model.onnx> <cpu|xnnpack|nnapi|nnapi_cpu_off|nnapi_fp16> <intra threads> [runs] [spin 0|1]
// Prints the median, the 90th percentile and the first run, in ms, and a checksum of the first output so that two
// settings can be seen to give the same answer on the same (fixed, made-up) input.
#define _POSIX_C_SOURCE 200809L
#include "onnxruntime_c_api.h"
#include "nnapi_provider_factory.h"

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

static double
now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static int
cmp_d(const void *a, const void *b)
{
	double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}

int
main(int argc, char **argv)
{
	if (argc < 4) {
		fprintf(stderr, "usage: %s <model.onnx> <cpu|xnnpack|nnapi|nnapi_cpu_off|nnapi_fp16> <threads> [runs] [spin]\n", argv[0]);
		return 2;
	}
	const char *ep = argv[2];
	int threads = atoi(argv[3]), runs = argc > 4 ? atoi(argv[4]) : 200, spin = argc > 5 ? atoi(argv[5]) : 0;
	A = OrtGetApiBase()->GetApi(ORT_API_VERSION);
	OrtEnv *env;
	CHECK(A->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "bench", &env));
	OrtSessionOptions *so;
	CHECK(A->CreateSessionOptions(&so));
	CHECK(A->SetSessionGraphOptimizationLevel(so, ORT_ENABLE_ALL));
	CHECK(A->SetIntraOpNumThreads(so, threads));
	CHECK(A->AddSessionConfigEntry(so, "session.intra_op.allow_spinning", spin ? "1" : "0"));
	if (strcmp(ep, "xnnpack") == 0) {
		char n[8];
		snprintf(n, sizeof n, "%d", threads);
		const char *k[] = {"intra_op_num_threads"}, *v[] = {n};
		CHECK(A->SessionOptionsAppendExecutionProvider(so, "XNNPACK", k, v, 1));
	} else if (strncmp(ep, "nnapi", 5) == 0) {
		uint32_t flags = 0;
		if (strcmp(ep, "nnapi_cpu_off") == 0) flags |= NNAPI_FLAG_CPU_DISABLED;
		if (strcmp(ep, "nnapi_fp16") == 0) flags |= NNAPI_FLAG_USE_FP16;
		CHECK(OrtSessionOptionsAppendExecutionProvider_Nnapi(so, flags));
	}
	double t0 = now_ms();
	OrtSession *s;
	CHECK(A->CreateSession(env, argv[1], so, &s));
	double load = now_ms() - t0;

	OrtAllocator *al;
	CHECK(A->GetAllocatorWithDefaultOptions(&al));
	OrtMemoryInfo *mi;
	CHECK(A->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mi));
	size_t ni, no;
	CHECK(A->SessionGetInputCount(s, &ni));
	CHECK(A->SessionGetOutputCount(s, &no));
	char *in_names[8], *out_names[8];
	OrtValue *in[8] = {0}, *out[8] = {0};
	uint32_t seed = 12345;
	for (size_t i = 0; i < ni && i < 8; i++) {
		CHECK(A->SessionGetInputName(s, i, al, &in_names[i]));
		OrtTypeInfo *ti;
		CHECK(A->SessionGetInputTypeInfo(s, i, &ti));
		const OrtTensorTypeAndShapeInfo *sh;
		CHECK(A->CastTypeInfoToTensorInfo(ti, &sh));
		size_t nd;
		CHECK(A->GetDimensionsCount(sh, &nd));
		int64_t dims[8];
		CHECK(A->GetDimensions(sh, dims, nd));
		size_t n = 1;
		for (size_t d = 0; d < nd; d++) {
			if (dims[d] < 1) dims[d] = 1;
			n *= (size_t)dims[d];
		}
		float *data = malloc(n * sizeof(float));
		for (size_t j = 0; j < n; j++) {
			seed = seed * 1664525u + 1013904223u;
			data[j] = (float)(seed >> 8) / (float)(1 << 24); // 0..1, the same every time
		}
		CHECK(A->CreateTensorWithDataAsOrtValue(mi, data, n * sizeof(float), dims, nd, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in[i]));
		A->ReleaseTypeInfo(ti);
	}
	for (size_t i = 0; i < no && i < 8; i++) CHECK(A->SessionGetOutputName(s, i, al, &out_names[i]));

	double *ms = malloc(sizeof(double) * runs), first = 0, sum0 = 0;
	for (int r = 0; r < runs; r++) {
		for (size_t i = 0; i < no; i++) {
			if (out[i]) A->ReleaseValue(out[i]);
			out[i] = NULL;
		}
		double a = now_ms();
		CHECK(A->Run(s, NULL, (const char *const *)in_names, (const OrtValue *const *)in, ni, (const char *const *)out_names, no, out));
		ms[r] = now_ms() - a;
		if (r == 0) {
			first = ms[0];
			float *o;
			CHECK(A->GetTensorMutableData(out[0], (void **)&o));
			OrtTensorTypeAndShapeInfo *osh;
			CHECK(A->GetTensorTypeAndShape(out[0], &osh));
			size_t on;
			CHECK(A->GetTensorShapeElementCount(osh, &on));
			for (size_t j = 0; j < on; j++) sum0 += o[j];
			A->ReleaseTensorTypeAndShapeInfo(osh);
		}
	}
	qsort(ms + 1, runs - 1, sizeof(double), cmp_d);
	printf("%-14s threads %d spin %d: median %.2f ms, 90th %.2f, first %.1f, load %.0f ms, output sum %.5f\n", ep, threads, spin,
	       ms[1 + (runs - 1) / 2], ms[1 + (runs - 1) * 9 / 10], first, load, sum0);
	fflush(stdout);
	_Exit(0); // ONNX Runtime's own threads are still about: leave without its destructors
	return 0;
}
