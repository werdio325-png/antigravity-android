import socket

_orig_gai = socket.getaddrinfo

def _dns_resolve(host):
    if not isinstance(host, str) or not host:
        raise socket.gaierror(-2, 'Name or service not known')
    # If already IP, return as-is
    parts = host.split('.')
    if len(parts) == 4 and all(p.isdigit() and 0 <= int(p) <= 255 for p in parts):
        return host
    query = b'\xaa\xbb\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00'
    for part in host.split('.'):
        query += bytes([len(part)]) + part.encode('ascii')
    query += b'\x00\x00\x01\x00\x01'
    for dns_ip in ('8.8.8.8', '1.1.1.1'):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(2.5)
            s.sendto(query, (dns_ip, 53))
            data, _ = s.recvfrom(1024)
            s.close()
            idx = data.rfind(b'\x00\x01\x00\x01')
            if idx != -1:
                return '.'.join(str(b) for b in data[idx+10:idx+14])
        except Exception:
            continue
    raise socket.gaierror(-2, f'Name or service not known: {host}')

def _patched_gai(host, port, family=0, type=0, proto=0, flags=0):
    try:
        return _orig_gai(host, port, family, type, proto, flags)
    except Exception:
        ip = _dns_resolve(host)
        return _orig_gai(ip, port, family, type, proto, flags)

socket.getaddrinfo = _patched_gai
