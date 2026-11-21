#!/usr/bin/env python3
"""aaue_http.py - turn an AAUE capture stream into readable HTTP exchanges.

AAUE frames are raw TLS plaintext slices: one frame per SSL_write call, and one
frame per SSL_read return. A single HTTP message is therefore spread over many
frames, and the body is usually chunked + gzip. This tool reassembles per
connection, parses HTTP/1.x, de-chunks, decompresses, and pairs requests with
responses.

Usage:
    python aaue_http.py CAPTURE.aaue                    # summary table
    python aaue_http.py CAPTURE.aaue --dump out/        # also write bodies
    python aaue_http.py CAPTURE.aaue --json out.ndjson  # machine readable
    python aaue_http.py CAPTURE.aaue --body 3           # print exchange #3

Notes:
    - direction 1 = outbound (SSL_write, i.e. requests)
    - direction 2 = inbound  (SSL_read, i.e. responses)
    If a read-direction stream starts with a request line, write/read were
    attached to the wrong functions; the tool reports this instead of silently
    producing garbage.
"""

import argparse
import gzip
import json
import os
import re
import struct
import sys
import zlib

HDR = struct.Struct("<IIIIIQ")  # magic, pid, uid, direction, length, ssl_ptr
MAGIC = 0x45554141

DIR_OUT = 1
DIR_IN = 2

REQ_RE = re.compile(rb"(?m)^([A-Z]{3,7}) (\S+) HTTP/1\.[01]\r?\n")
RES_RE = re.compile(rb"(?m)^HTTP/1\.[01] (\d{3})(?: [^\r\n]*)?")

# Response status codes that never carry a body.
NO_BODY_STATUS = {204, 304}


# --------------------------------------------------------------------------- #
# frame layer
# --------------------------------------------------------------------------- #
MAGIC_BYTES = struct.pack("<I", MAGIC)
KNOWN_DIRECTIONS = {0, 1, 2, 9, 10, 11, 12}


def plausible_frame(data, pos):
    """Reject a byte pattern that merely looks like a header."""
    if pos + HDR.size > len(data):
        return False
    magic, pid, uid, direction, length, _ssl = HDR.unpack_from(data, pos)
    return (magic == MAGIC and pid != 0 and uid != 0
            and direction in KNOWN_DIRECTIONS and length <= (8 << 20)
            and pos + HDR.size + length <= len(data))


def iter_frames(path, resync=True):
    """Yield (pid, uid, direction, ssl_ptr, payload).

    Captures get truncated by `timeout`, concatenated by hand, or start mid
    frame. On a magic mismatch we look ahead for the next plausible header
    instead of giving up, and report how many bytes were skipped.
    """
    data = open(path, "rb").read()
    off = 0
    total = len(data)
    while off + HDR.size <= total:
        magic, pid, uid, direction, length, ssl_ptr = HDR.unpack_from(data, off)
        if magic != MAGIC:
            nxt = off
            found = -1
            while True:
                nxt = data.find(MAGIC_BYTES, nxt + 1)
                if nxt < 0:
                    break
                if plausible_frame(data, nxt):
                    found = nxt
                    break
            if not resync or found < 0:
                yield ("bad_magic", off, magic)
                return
            yield ("resync", off, found - off)
            off = found
            continue
        start = off + HDR.size
        end = start + length
        if end > total:
            yield ("truncated", off, length)
            return
        yield (pid, uid, direction, ssl_ptr, data[start:end])
        off = end
    if off != total:
        yield ("trailing", off, total - off)


def collect(path):
    """Group frame payloads per (pid, ssl_ptr) in arrival order."""
    conns = {}
    stats = {"frames": 0, "bytes": 0, "dirs": {}, "anomalies": [], "resyncs": []}
    for item in iter_frames(path):
        if isinstance(item[0], str):
            if item[0] == "resync":
                stats["resyncs"].append((item[1], item[2]))
                continue
            stats["anomalies"].append("%s at 0x%x (arg=%s)" % (item[0], item[1], item[2]))
            break
        pid, uid, direction, ssl_ptr, payload = item
        stats["frames"] += 1
        stats["bytes"] += len(payload)
        stats["dirs"][direction] = stats["dirs"].get(direction, 0) + 1
        key = (pid, ssl_ptr)
        slot = conns.setdefault(key, {"out": bytearray(), "in": bytearray(), "uid": uid})
        if direction == DIR_OUT:
            slot["out"].extend(payload)
        elif direction == DIR_IN:
            slot["in"].extend(payload)
    return conns, stats


# --------------------------------------------------------------------------- #
# http layer
# --------------------------------------------------------------------------- #
def parse_headers(block):
    headers = {}
    for line in block.split(b"\r\n")[1:]:
        if b":" not in line:
            continue
        k, v = line.split(b":", 1)
        key = k.strip().lower().decode("latin1")
        val = v.strip().decode("latin1")
        if key in headers:  # keep duplicates observable
            headers[key] += ", " + val
        else:
            headers[key] = val
    return headers


def read_chunked(buf, pos):
    """Return (body, next_pos, complete)."""
    body = bytearray()
    q = pos
    while True:
        e = buf.find(b"\r\n", q)
        if e < 0:
            return bytes(body), len(buf), False
        raw = buf[q:e].split(b";")[0].strip()
        try:
            size = int(raw, 16)
        except ValueError:
            return bytes(body), q, False
        q = e + 2
        if size == 0:
            trailer_end = buf.find(b"\r\n", q)
            return bytes(body), (trailer_end + 2 if trailer_end >= 0 else len(buf)), True
        if q + size > len(buf):
            return bytes(body), len(buf), False
        body += buf[q:q + size]
        q += size
        if buf[q:q + 2] == b"\r\n":
            q += 2
    # unreachable


def body_span(buf, pos, headers, status=None):
    """Extract a body per RFC 7230 framing. Returns (body, next_pos, complete)."""
    if status is not None and (status in NO_BODY_STATUS or 100 <= status < 200):
        return b"", pos, True
    te = headers.get("transfer-encoding", "").lower()
    if "chunked" in te:
        return read_chunked(buf, pos)
    cl = headers.get("content-length")
    if cl is not None:
        try:
            n = int(cl.split(",")[0].strip())
        except ValueError:
            n = 0
        if pos + n > len(buf):
            return bytes(buf[pos:]), len(buf), False
        return bytes(buf[pos:pos + n]), pos + n, True
    return b"", pos, True


def decompress(body, encoding):
    """Decode Content-Encoding. Returns (bytes, applied_name_or_None)."""
    enc = (encoding or "").lower().strip()
    if not enc or not body:
        return body, None
    for token in [t.strip() for t in enc.split(",") if t.strip()]:
        if token == "gzip" and body[:2] == b"\x1f\x8b":
            try:
                body = gzip.decompress(body)
                continue
            except Exception:
                return body, "gzip-failed"
        if token == "deflate":
            for wbits in (zlib.MAX_WBITS, -zlib.MAX_WBITS):
                try:
                    body = zlib.decompress(body, wbits)
                    break
                except Exception:
                    continue
            else:
                return body, "deflate-failed"
        if token == "br":
            try:
                import brotli  # optional
                body = brotli.decompress(body)
            except Exception:
                return body, "br-unavailable"
    return body, enc


def parse_requests(buf):
    """Yield dicts for each request found in an outbound stream."""
    out = []
    pos = 0
    while True:
        m = REQ_RE.search(buf, pos)
        if not m:
            break
        head_end = buf.find(b"\r\n\r\n", m.start())
        if head_end < 0:
            break
        headers = parse_headers(buf[m.start():head_end])
        body, nxt, complete = body_span(buf, head_end + 4, headers)
        out.append({
            "method": m.group(1).decode("latin1"),
            "target": m.group(2).decode("latin1"),
            "headers": headers,
            "body": body,
            "complete": complete,
        })
        pos = nxt if complete and nxt > head_end else head_end + 4
    return out


def parse_responses(buf):
    """Yield dicts for each response found in an inbound stream."""
    out = []
    pos = 0
    while True:
        m = RES_RE.search(buf, pos)
        if not m:
            break
        head_end = buf.find(b"\r\n\r\n", m.start())
        if head_end < 0:
            break
        status = int(m.group(1))
        headers = parse_headers(buf[m.start():head_end])
        body, nxt, complete = body_span(buf, head_end + 4, headers, status)
        out.append({
            "status": status,
            "reason": m.group(0).decode("latin1").split(" ", 2)[-1].strip(),
            "headers": headers,
            "raw_body": body,
            "complete": complete,
        })
        pos = nxt if nxt > head_end else head_end + 4
        if not complete:
            break
    return out


def looks_like_request(blob):
    return bool(REQ_RE.search(blob[:2048]))


def looks_like_response(blob):
    return bool(RES_RE.search(blob[:2048]))


# --------------------------------------------------------------------------- #
# pairing
# --------------------------------------------------------------------------- #
def build_exchanges(conns, stats):
    exchanges = []
    warnings = []
    for (pid, ssl), slot in conns.items():
        out_buf, in_buf = bytes(slot["out"]), bytes(slot["in"])

        # Direction self-check: catches a write/read swap in the offset profile.
        if looks_like_request(in_buf) and not looks_like_response(in_buf):
            warnings.append(
                "conn ssl=0x%x: inbound direction carries a request line - "
                "write/read probes look swapped" % ssl)
        if looks_like_response(out_buf) and not looks_like_request(out_buf):
            warnings.append(
                "conn ssl=0x%x: outbound direction carries a status line - "
                "write/read probes look swapped" % ssl)

        reqs = parse_requests(out_buf) if out_buf else []
        ress = parse_responses(in_buf) if in_buf else []

        for i, res in enumerate(ress):
            req = reqs[i] if i < len(reqs) else None
            body, applied = decompress(res["raw_body"], res["headers"].get("content-encoding"))
            exchanges.append({
                "pid": pid,
                "ssl": ssl,
                "seq": len(exchanges) + 1,
                "method": req["method"] if req else None,
                "target": req["target"] if req else None,
                "host": (req["headers"].get("host") if req else None),
                "req_headers": req["headers"] if req else None,
                "req_body_len": len(req["body"]) if req else None,
                "status": res["status"],
                "resp_headers": res["headers"],
                "encoding": res["headers"].get("content-encoding"),
                "raw_len": len(res["raw_body"]),
                "len": len(body),
                "decoded": applied,
                "complete": res["complete"],
                "content_type": res["headers"].get("content-type", ""),
                "body": body,
            })
        stats["orphan_requests"] = stats.get("orphan_requests", 0) + max(0, len(reqs) - len(ress))
    stats["direction_warnings"] = warnings
    return exchanges


# --------------------------------------------------------------------------- #
# output
# --------------------------------------------------------------------------- #
def slug(text, limit=48):
    text = re.sub(r"^[a-z]+://", "", text)
    text = re.sub(r"[^A-Za-z0-9._/-]+", "_", text)
    text = text.strip("_/").replace("/", "_")
    return (text[:limit] or "root").strip("_")


def ext_for(content_type, body=b""):
    # Content first: servers routinely label JSON as text/plain or octet-stream,
    # and validating a truncated prefix always fails on large bodies.
    if body[:1] in (b"{", b"["):
        return ".json"
    if body[:8] == b"\x89PNG\r\n\x1a\n":
        return ".png"
    if body[:3] == b"\xff\xd8\xff":
        return ".jpg"
    if body[:4] == b"RIFF" and body[8:12] == b"WEBP":
        return ".webp"
    if body[:2] == b"\x1f\x8b":
        return ".gz"
    ct = (content_type or "").lower()
    if "json" in ct:
        return ".json"
    if "html" in ct:
        return ".html"
    if "javascript" in ct:
        return ".js"
    if "css" in ct:
        return ".css"
    if "image/png" in ct:
        return ".png"
    if "image/jpeg" in ct:
        return ".jpg"
    if "image/webp" in ct:
        return ".webp"
    if "protobuf" in ct or "octet-stream" in ct:
        return ".bin"
    if "text/" in ct:
        return ".txt"
    return ".bin"


def printable_head(body, n=70):
    chunk = body[:n]
    text = "".join(chr(c) if 32 <= c < 127 else ("." if c < 128 else "?") for c in chunk)
    return text


def main(argv=None):
    ap = argparse.ArgumentParser(description="Reassemble AAUE TLS frames into HTTP exchanges")
    ap.add_argument("capture")
    ap.add_argument("--dump", metavar="DIR", help="write decoded bodies into DIR")
    ap.add_argument("--json", metavar="FILE", help="write NDJSON records (body omitted)")
    ap.add_argument("--body", type=int, metavar="N", help="print the body of exchange N")
    ap.add_argument("--host", help="only exchanges whose Host contains this substring")
    ap.add_argument("--path", help="only exchanges whose target contains this substring")
    ap.add_argument("--status", type=int, help="only this status code")
    ap.add_argument("--min-len", type=int, default=0, help="skip decoded bodies shorter than N")
    ap.add_argument("--list", action="store_true", help="summary only, never write files")
    args = ap.parse_args(argv)

    if not os.path.exists(args.capture):
        print("no such capture: %s" % args.capture, file=sys.stderr)
        return 2

    conns, stats = collect(args.capture)
    exchanges = build_exchanges(conns, stats)

    print("capture      : %s" % args.capture)
    print("frames       : %d (%.1f KiB, dirs=%s)" % (
        stats["frames"], stats["bytes"] / 1024.0,
        ",".join("%d:%d" % kv for kv in sorted(stats["dirs"].items()))))
    print("connections  : %d" % len(conns))
    print("exchanges    : %d (orphan requests: %d)" % (
        len(exchanges), stats.get("orphan_requests", 0)))
    for w in stats.get("direction_warnings", []):
        print("WARN         : %s" % w)
    for a in stats.get("anomalies", []):
        print("WARN         : %s" % a)
    for off, skipped in stats.get("resyncs", []):
        print("RESYNC       : skipped %d stray bytes at 0x%x" % (skipped, off))

    sel = []
    for ex in exchanges:
        if args.host and args.host.lower() not in (ex["host"] or "").lower():
            continue
        if args.path and args.path not in (ex["target"] or ""):
            continue
        if args.status and ex["status"] != args.status:
            continue
        if ex["len"] < args.min_len:
            continue
        sel.append(ex)

    print("-" * 118)
    print("%-4s %-6s %-3s %-9s %-9s %-22s %s" % (
        "idx", "method", "st", "raw", "decoded", "host", "target"))
    print("-" * 118)
    for ex in sel:
        print("%-4d %-6s %-3d %-9d %-9d %-22s %s" % (
            ex["seq"], ex["method"] or "-", ex["status"],
            ex["raw_len"], ex["len"], (ex["host"] or "-")[:22],
            (ex["target"] or "-")[:58]))

    if args.body:
        target = next((e for e in exchanges if e["seq"] == args.body), None)
        if not target:
            print("no exchange #%d" % args.body, file=sys.stderr)
        else:
            sys.stdout.write("\n===== #%d %s %s -> %d (%s) =====\n" % (
                target["seq"], target["method"], target["target"],
                target["status"], target["content_type"] or "-"))
            sys.stdout.flush()
            sys.stdout.buffer.write(target["body"])
            sys.stdout.buffer.write(b"\n")
        return 0

    if args.dump and not args.list:
        os.makedirs(args.dump, exist_ok=True)
        for ex in sel:
            name = "%03d_%s_%s%s" % (
                ex["seq"], (ex["method"] or "GET").lower(),
                slug(ex["target"] or "root"), ext_for(ex["content_type"], ex["body"]))
            with open(os.path.join(args.dump, name), "wb") as f:
                f.write(ex["body"])
        print("\nbodies written to %s (%d files)" % (args.dump, len(sel)))

    if args.json and not args.list:
        with open(args.json, "w", encoding="utf-8") as f:
            for ex in sel:
                rec = {k: v for k, v in ex.items() if k != "body"}
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        print("records written to %s (%d lines)" % (args.json, len(sel)))

    return 0


if __name__ == "__main__":
    sys.exit(main())
