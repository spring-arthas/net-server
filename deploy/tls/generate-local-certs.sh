#!/bin/sh
set -eu

# [修改] 统一生成符合严格校验要求的本地 CA 和 net-server 服务端证书。
if [ "$#" -ne 2 ]; then
    echo "usage: $0 <output-directory> <public-ip>" >&2
    exit 64
fi

output_directory=$1
NET_SERVER_PUBLIC_IP=$2
export NET_SERVER_PUBLIC_IP

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
openssl_config="$script_directory/openssl-local.cnf"

umask 077
mkdir -p "$output_directory"

ca_key="$output_directory/chat-storage-local-ca.key"
ca_certificate="$output_directory/chat-storage-local-ca.crt"
server_key="$output_directory/net-server.key"
server_request="$output_directory/net-server.csr"
server_certificate="$output_directory/net-server.crt"
NET_SERVER_TLS_KEYSTORE_PASSWORD=${NET_SERVER_TLS_KEYSTORE_PASSWORD-}
export NET_SERVER_TLS_KEYSTORE_PASSWORD

openssl req -new -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
    -config "$openssl_config" -extensions ca_extensions \
    -subj "/CN=ChatStorage Local Test CA" \
    -keyout "$ca_key" -out "$ca_certificate"

openssl req -new -newkey rsa:3072 -nodes -sha256 \
    -config "$openssl_config" \
    -keyout "$server_key" -out "$server_request"

openssl x509 -req -sha256 -days 397 \
    -in "$server_request" \
    -CA "$ca_certificate" -CAkey "$ca_key" -CAcreateserial \
    -extfile "$openssl_config" -extensions server_extensions \
    -out "$server_certificate"

cat "$server_key" "$server_certificate" "$ca_certificate" > "$output_directory/net-server.pem"
openssl pkcs12 -export \
    -passout env:NET_SERVER_TLS_KEYSTORE_PASSWORD \
    -inkey "$server_key" \
    -in "$server_certificate" \
    -certfile "$ca_certificate" \
    -out "$output_directory/net-server.p12"
openssl x509 -in "$ca_certificate" -outform der -out "$output_directory/chat-storage-local-ca.der"

openssl verify -x509_strict -CAfile "$ca_certificate" "$server_certificate"
