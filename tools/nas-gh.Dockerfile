# Local-only helper CLI for Synology NAS. This image contains no credentials.
FROM alpine:3.23
RUN apk add --no-cache ca-certificates github-cli
ENTRYPOINT ["gh"]
