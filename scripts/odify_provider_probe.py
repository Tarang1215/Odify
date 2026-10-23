#!/usr/bin/env python3
"""Small, respectful instance health probe: one music query per provider URL.
Metadata only; logs HTTP status, latency, candidate count. Never touches MASTER.
"""
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parent))
from odify_link_multi_backend import fetch_json, parse_candidates, provider_urls, utc_now
from urllib.parse import urlparse

PIPED=[
"https://pipedapi.ducks.party",
"https://api.piped.private.coffee",
"https://pipedapi.adminforge.de",
]
INVIDIOUS=[
"https://invidious.nerdvpn.de",
"https://yt.chocolatemoo53.com",
"https://invidious.tiekoetter.com",
]
def main():
    report=[]
    for provider,origins in (("piped",PIPED),("invidious",INVIDIOUS)):
        for origin in origins:
            url=next(provider_urls(provider,[origin],"BTS Dynamite official audio"))
            result=fetch_json(url,5)
            if len(result)==4:
                _,code,ms,error=result
                row=dict(provider=provider,host=urlparse(origin).hostname,
                         http_status=code,latency_ms=ms,valid_response=False,
                         candidates=0,error=error)
            else:
                data,code,ms=result
                try:
                    tracks=parse_candidates(provider,data)
                    row=dict(provider=provider,host=urlparse(origin).hostname,
                             http_status=code,latency_ms=ms,valid_response=True,
                             candidates=len(tracks),error="")
                except Exception as e:
                    row=dict(provider=provider,host=urlparse(origin).hostname,
                             http_status=code,latency_ms=ms,valid_response=False,
                             candidates=0,error=str(e)[:180])
            report.append(row)
            print("ODIFY_INSTANCE_PROBE "+json.dumps(row,ensure_ascii=False),flush=True)
    output=Path(".local/odify_provider_probe.json");output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps({"date":utc_now(),"probe":report},ensure_ascii=False,indent=2))
    print("ODIFY_PROBE_SUMMARY "+json.dumps({
       "instances":len(report),"healthy":sum(row["valid_response"] for row in report),
       "total_candidates":sum(row["candidates"] for row in report)},ensure_ascii=False),flush=True)
if __name__=="__main__":main()
