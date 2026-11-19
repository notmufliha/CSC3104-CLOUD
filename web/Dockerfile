# The ScamGuard web app image (scam/web): the page (web/), the apps' call scripts, the apps' on-device models
# (three small JSON models + MobileBERT, for privacy levels 2 and 3 in the browser) and onnxruntime-web, which runs
# MobileBERT in the page. Served by nginx. Used by both ways of running the backend:
#   Kubernetes: demo/up.ps1 / up.sh build it, demo/k8s/11-web.yaml runs it behind the Ingress (https://127.0.0.1:8443/)
#   compose:    the "edge" service builds it and adds the /api/ proxy (demo/compose-edge.conf; http://127.0.0.1:8088/)
# Build from the repo root:  docker build -t scam/web:demo -f web/Dockerfile .
# (web/Dockerfile.dockerignore keeps the build context to the few folders it needs.)
FROM nginx:1.27-alpine

# onnxruntime-web 1.20.1 (MIT), the WebAssembly build only; pinned by checksum. Needs internet at build time, like the
# bert image's model download.
ARG ORT=https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1
ADD --checksum=sha256:e66568724f8848e57cc2a56e4bea3a5b86ce3ff81b2da11eac8ed7ab02b27bd4 ${ORT}/dist/ort.wasm.min.js /usr/share/nginx/html/vendor/ort/
ADD --checksum=sha256:207d02be4591c156b0a98f024f3d58005b5b04c92274d759fb390338c63559ea ${ORT}/dist/ort-wasm-simd-threaded.wasm /usr/share/nginx/html/vendor/ort/
ADD --checksum=sha256:745eb7c0ce6f18a6aa521971b2877babc7ffb27eecb58ab3bc6e5ef4692672e8 ${ORT}/dist/ort-wasm-simd-threaded.mjs /usr/share/nginx/html/vendor/ort/

COPY web/index.html web/style.css web/app.js web/ondevice.js web/strings.json web/learn.json /usr/share/nginx/html/
COPY android-app/app/src/main/assets/models/ /usr/share/nginx/html/ondevice/
COPY android-app/app/src/main/assets/scripts/ /usr/share/nginx/scripts/
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
RUN chmod -R a+rX /usr/share/nginx
