"""
try_onnx.py - Workstream G.2: can skl2onnx convert the three English models? (Kept as the evidence for using the
JSON export instead; see export_models.py.) Converts each model's inner scikit-learn pipeline (the cleaning stays
outside: it is custom Python) and compares ONNX Runtime's probabilities with scikit-learn's.

  python android-app/tools/try_onnx.py urgency       backend/workers/urgency_en/model/urgency_en_v4.joblib backend/workers/urgency_en
  python android-app/tools/try_onnx.py money         backend/workers/money_en/model/E_model.joblib backend/workers/money_en
  python android-app/tools/try_onnx.py impersonation backend/workers/impersonation_en/model/model.pkl backend/workers/impersonation_en

Result (2026-11-05, skl2onnx 1.20.0, onnxruntime 1.31.1):
  urgency        converts, but onnxruntime can't load it: "Can not digest tokenexp: invalid perl operator: (?u"
  money          NotImplementedError: only tokenizer='word' is fully supported (it uses analyzer='char_wb')
  impersonation  NotImplementedError: same (analyzer='char_wb')
"""
import sys, joblib, numpy as np, warnings
warnings.filterwarnings("ignore")
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import StringTensorType
import onnxruntime as ort
name, path, wdir = sys.argv[1:4]
sys.path.insert(0, wdir)
m = joblib.load(path)
if name == "urgency":
    pipe = m["pipeline"]
    from text_norm import normalize as clean
elif name == "money":
    pipe = m.pipe
    from money_norm import clean_text as clean
else:
    from sklearn.pipeline import Pipeline
    pipe = Pipeline(m.base.steps[1:])   # drop the FunctionTransformer(prep): custom Python, can't be ONNX
    from text_utils import prep
    clean = lambda t: prep([t])[0]
texts = ["You must pay within 24 hours or your account will be frozen.", "This is Officer Tan from SPF, read me the OTP.",
         "Hi mum, see you at dinner tonight.", "Transfer $5,000 to the safe account now", "Your parcel from DHL is held at customs"]
X = [clean(t) for t in texts]
try:
    onx = convert_sklearn(pipe, initial_types=[("text", StringTensorType([None, 1]))],
                          options={id(pipe.steps[-1][1]): {"zipmap": False}}, target_opset=17)
except Exception as e:
    print(f"{name}: CONVERSION FAILED: {type(e).__name__}: {str(e).splitlines()[0][:300]}"); sys.exit(0)
sess = ort.InferenceSession(onx.SerializeToString())
got = sess.run(None, {"text": np.array(X, dtype=object).reshape(-1, 1)})[1][:, 1]
want = pipe.predict_proba(X)[:, 1]
print(f"{name}: converted, {len(onx.SerializeToString())/1e6:.1f} MB; max |onnx - sklearn| on raw LR proba = {np.abs(got - want).max():.4f}")
for t, a, b in zip(X, want, got): print(f"   sklearn {a:.4f}  onnx {b:.4f}  | {t[:60]}")
