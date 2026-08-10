import yaml, glob, os, sys

base = 'deploy/k8s'
docs = []
files = sorted(glob.glob(os.path.join(base, '*.yaml')))
for f in files:
    with open(f, encoding='utf-8') as fh:
        for d in yaml.safe_load_all(fh):
            if d:
                docs.append((f, d))

errors = []
configmaps, secrets, services, deployments, statefulsets = {}, {}, {}, {}, {}

def workload_spec(d):
    t = d.get('spec', {}).get('template', {}).get('spec')
    return t if isinstance(t, dict) else {}

for f, d in docs:
    kind = d.get('kind'); name = (d.get('metadata') or {}).get('name')
    if kind == 'ConfigMap':
        configmaps[name] = list((d.get('data') or {}).keys())
    elif kind == 'Secret':
        secrets[name] = list((d.get('data') or {}).keys())
    elif kind == 'Service':
        services[name] = (d.get('spec') or {}).get('selector', {})
    elif kind == 'Deployment':
        deployments[name] = workload_spec(d).get('template', {}).get('metadata', {}).get('labels', {})
    elif kind == 'StatefulSet':
        statefulsets[name] = workload_spec(d).get('template', {}).get('metadata', {}).get('labels', {})

for f, d in docs:
    kind = d.get('kind'); name = (d.get('metadata') or {}).get('name')
    if not kind or not name:
        errors.append(f"{f}: 缺 kind/name")
    if kind in ('Deployment', 'StatefulSet', 'Service', 'ConfigMap', 'Secret', 'Ingress') and 'apiVersion' not in d:
        errors.append(f"{f}: 缺 apiVersion")
    spec = workload_spec(d)
    if not spec:
        continue
    for c in spec.get('containers', []) or []:
        for e in c.get('env', []) or []:
            vr = e.get('valueFrom', {}) or {}
            sk = vr.get('secretKeyRef')
            if sk:
                sn, key = sk.get('name'), sk.get('key')
                if sn not in secrets:
                    errors.append(f"{f}: secretKeyRef 引用不存在的 Secret {sn}")
                elif key not in secrets[sn]:
                    errors.append(f"{f}: Secret {sn} 无 key {key}")
            cm = vr.get('configMapKeyRef')
            if cm:
                cn, key = cm.get('name'), cm.get('key')
                if cn not in configmaps:
                    errors.append(f"{f}: configMapKeyRef 引用不存在的 ConfigMap {cn}")
                elif key not in configmaps[cn]:
                    errors.append(f"{f}: ConfigMap {cn} 无 key {key}")
        for v in spec.get('volumes', []) or []:
            cm = v.get('configMap')
            if cm:
                cn = cm.get('name')
                if cn not in configmaps:
                    errors.append(f"{f}: volume 引用不存在的 ConfigMap {cn}")
                else:
                    for it in cm.get('items', []) or []:
                        if it.get('key') not in configmaps[cn]:
                            errors.append(f"{f}: volume ConfigMap {cn} 无 key {it.get('key')}")
    for ef in spec.get('envFrom', []) or []:
        cmref = ef.get('configMapRef')
        if cmref and cmref.get('name') not in configmaps:
            errors.append(f"{f}: envFrom 引用不存在的 ConfigMap {cmref.get('name')}")
    if kind == 'Ingress':
        for rule in (d.get('spec') or {}).get('rules', []) or []:
            for p in (rule.get('http') or {}).get('paths', []) or []:
                svc = (p.get('backend') or {}).get('service', {}).get('name')
                if svc and svc not in services:
                    errors.append(f"{f}: ingress 引用不存在的 Service {svc}")
    if kind == 'Service':
        sel = (d.get('spec') or {}).get('selector', {})
        matched = any(all(w.get(k) == v for k, v in sel.items())
                      for w in list(deployments.values()) + list(statefulsets.values()))
        if not matched:
            errors.append(f"{f}: Service {name} selector 未匹配任何 workload {sel}")

print("ConfigMaps :", list(configmaps.keys()))
print("Secrets    :", list(secrets.keys()))
print("Services   :", list(services.keys()))
print("Deployments:", list(deployments.keys()))
print("StatefulSet:", list(statefulsets.keys()))
print("ERRORS     :", len(errors))
for e in errors:
    print("  -", e)
sys.exit(1 if errors else 0)
