"""Quantise the two Mercury hand models to INT8 (QDQ, per-channel weights) with calibration inputs dumped from the tracker,
then compare outputs with the float models on held-out inputs.
usage: python quant.py DUMPDIR OUTDIR [percentile|minmax]"""
import sys, os, numpy as np
import onnx, onnxruntime as ort
from onnxruntime.quantization import quantize_static, CalibrationDataReader, QuantFormat, QuantType, CalibrationMethod

dump, out = sys.argv[1], sys.argv[2]
method = sys.argv[3] if len(sys.argv) > 3 else "minmax"
models = r"C:\Users\asddd\Downloads\Xreal-claude-pixel-xreal-xr-gaming-cguv7y\_latest\app\src\main\assets\hand-models"
os.makedirs(out, exist_ok=True)

def rec(name, n):
    a = np.fromfile(os.path.join(dump, name + ".bin"), dtype=np.float32)
    return a[: (len(a) // n) * n].reshape(-1, n)

det = rec("det", 160 * 160).reshape(-1, 1, 160, 160)
kimg = rec("kpimg", 128 * 128).reshape(-1, 1, 128, 128)
klast = rec("kplast", 42); kuse = rec("kpuse", 1)
nk = min(len(kimg), len(klast), len(kuse))
print("samples: detector", len(det), "keypoint", nk)
kimg, klast, kuse = kimg[:nk], klast[:nk], kuse[:nk]

class Reader(CalibrationDataReader):
    def __init__(self, feeds): self.feeds = iter(feeds)
    def get_next(self): return next(self.feeds, None)

def split(n): idx = np.arange(n); return idx[idx % 5 != 0], idx[idx % 5 == 0]     # calibrate on 4/5, test on 1/5
cal_d, test_d = split(len(det)); cal_k, test_k = split(nk)
cal_d = cal_d[:300]; cal_k = cal_k[:300]
m = CalibrationMethod.MinMax if method == "minmax" else CalibrationMethod.Percentile

EXCL = os.environ.get("EXCL", "")
def excluded(src):
    mm = onnx.load(src); convs = [n for n in mm.graph.node if n.op_type == "Conv"]; ex = []
    for i, n in enumerate(convs):
        g = [a.i for a in n.attribute if a.name == "group"]; g = g[0] if g else 1
        if ("first" in EXCL and i == 0) or ("last3" in EXCL and i >= len(convs) - 3) or ("last6" in EXCL and i >= len(convs) - 6) or ("dw" in EXCL and g > 1): ex.append(n.name)
    return ex
def quant(src, dst, feeds):
    extra = {"ActivationSymmetric": False, "WeightSymmetric": True}
    if os.environ.get("PCT"): extra["CalibPercentile"] = float(os.environ["PCT"])
    quantize_static(src, dst, Reader(feeds), quant_format=QuantFormat.QDQ, activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
                    per_channel=True, calibrate_method=m, op_types_to_quantize=["Conv"], nodes_to_exclude=excluded(src), extra_options=extra)

fd = lambda i: {"inputImg": det[i:i + 1]}
fk = lambda i: {"inputImg": kimg[i:i + 1], "lastKeypoints": klast[i:i + 1], "useLastKeypoints": kuse[i:i + 1].reshape(1)}
quant(os.path.join(models, "grayscale_detection_160x160.onnx"), os.path.join(out, "grayscale_detection_160x160.onnx"), (fd(i) for i in cal_d))
quant(os.path.join(models, "grayscale_keypoint_jan18.onnx"), os.path.join(out, "grayscale_keypoint_jan18.onnx"), (fk(i) for i in cal_k))
for f in os.listdir(out): print(f, os.path.getsize(os.path.join(out, f)))

def session(p):
    o = ort.SessionOptions(); o.log_severity_level = 3; return ort.InferenceSession(p, o, providers=["CPUExecutionProvider"])
# detector agreement
a = session(os.path.join(models, "grayscale_detection_160x160.onnx")); b = session(os.path.join(out, "grayscale_detection_160x160.onnx"))
de = []; hit_f = hit_q = agree = 0; n = 0
for i in test_d:
    ra = a.run(None, fd(i)); rb = b.run(None, fd(i))
    for h in (0, 1):
        pa, pb = float(ra[0][0, h]), float(rb[0][0, h]); n += 1
        fa, fb = pa > 0.5, pb > 0.5; hit_f += fa; hit_q += fb; agree += (fa == fb)
        if fa and fb: de.append([abs(float(ra[1][0, h]) - float(rb[1][0, h])), abs(float(ra[2][0, h]) - float(rb[2][0, h])), abs(float(ra[3][0, h]) - float(rb[3][0, h]))])
de = np.array(de) if de else np.zeros((1, 3))
print(f"DETECTOR {n} slots: float says hand in {hit_f}, int8 in {hit_q}, same verdict {agree}/{n}; where both: |dcx| {de[:,0].mean():.4f} |dcy| {de[:,1].mean():.4f} |dsize| {de[:,2].mean():.4f} (the model's units; its size is of the order of 0.3-1)")
# keypoint agreement
a = session(os.path.join(models, "grayscale_keypoint_jan18.onnx")); b = session(os.path.join(out, "grayscale_keypoint_jan18.onnx"))
dist = []; dep = []; ex = []
for i in test_k:
    ra = a.run(None, fk(i)); rb = b.run(None, fk(i))
    ha = ra[0][0].reshape(21, -1); hb = rb[0][0].reshape(21, -1)
    ya, xa = np.unravel_index(ha.argmax(1), (22, 22)); yb, xb = np.unravel_index(hb.argmax(1), (22, 22))
    dist += list(np.hypot(xa - xb, ya - yb))                       # in heatmap cells; the crop is 128 px over 22 cells
    dep.append(np.abs(ra[1] - rb[1]).mean()); ex.append(np.abs(ra[2] - rb[2]).mean())
dist = np.array(dist)
print(f"KEYPOINT {len(test_k)} samples: argmax of the heatmap moves {dist.mean():.3f} cells on average (1 cell = 5.8 px of the 128 px crop), P95 {np.percentile(dist,95):.2f}, over 1 cell: {(dist>1).mean()*100:.1f}% of joints; depth err {np.mean(dep):.4f}, scalar_extras err {np.mean(ex):.4f}")
