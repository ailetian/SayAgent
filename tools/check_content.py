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
# use kb 10 from last run
for did in ["115b13d2-bb62-4f51-a7e5-a0a395e1cce6","204d36aa-f985-4e18-8cb9-b314922f62c8"]:
    r=json.loads(req("POST",BASE+"/knowledge/retrieve",tok,{"kbId":10,"query":"Hify knowledge base","topK":1}))
    c=r["data"]
    print("DOC",did[:8],"-> hits",len(c))
    if c:
        print("   content:", c[0]["content"][:160].replace("\n"," "))
