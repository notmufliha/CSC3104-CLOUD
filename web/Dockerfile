# The ScamGuard web app for the Kubernetes demo (demo/up.sh builds it as scam/web:demo). Static files only; the
# Ingress sends / here and /api/ to the gateway, so the page calls the gateway on its own address.
# docker compose doesn't use this image: its edge serves web/ straight from the repo (docker-compose.yml).
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html manifest.webmanifest /usr/share/nginx/html/
COPY css /usr/share/nginx/html/css
COPY js /usr/share/nginx/html/js
COPY vendor /usr/share/nginx/html/vendor
COPY icons /usr/share/nginx/html/icons
COPY assets /usr/share/nginx/html/assets
EXPOSE 8080
