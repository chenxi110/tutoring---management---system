import requests
B='http://localhost:8081/api'
t=requests.post(B+'/auth/login',json={'username':'admin','password':'admin123'},timeout=10).json()['token']
for ep in ['/users','/dashboard']:
    rr=requests.get(B+ep,headers={'Authorization':'Bearer '+t},timeout=10)
    print(ep, '=>', rr.text[:500])
