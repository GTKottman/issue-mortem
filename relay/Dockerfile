# The Issue Mortem relay as a small container. Needs RELAY_SECRET; listens on PORT (default 8080) behind your HTTPS
# proxy. docker build -t issue-mortem-relay relay/
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags=-s -o /relay .

FROM scratch
COPY --from=build /relay /relay
EXPOSE 8080
USER 65534
ENTRYPOINT ["/relay"]
