import urllib.request, json
BASE="http://localhost:9095/api"
def req(m,u,t=None,b=None):
    d=None;h={"Accept":"application/json"}
    if t:h["Authorization"]="Bearer "+t
    if b is not None:
        d=json.dumps(b).encode();h["Content-Type"]="application/json; charset=utf-8"
    r=urllib.request.Request(u,data=d,method=m,headers=h)
    with urllib.request.urlopen(r,timeout=60) as x:return x.read().decode()
tok=json.loads(req("POST",BASE+"/auth/login",b={"username":"admin","password":"admin123"}))["data"]["token"]
r=json.loads(req("POST",BASE+"/knowledge/retrieve",tok,{"kbId":10,"query":"PDF upload via Tika Spring Boot RAG pipeline","topK":3}))
print("hits",len(r["data"]))
for c in r["data"]:
    print("-", c["content"][:160].replace("\n"," "))
