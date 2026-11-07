#!/usr/bin/env python3
"""Trusted-side validation for untrusted PR QA input."""
import hashlib,json,os,re,stat,urllib.error,urllib.parse,urllib.request,zipfile
from pathlib import Path
REPO='bianwuya/Eta'; LIMIT=150*1024*1024
SHA=re.compile(r'^[0-9a-f]{40}$'); HASH=re.compile(r'^[0-9a-f]{64}$')
def need(x,msg):
    if not x: raise ValueError(msg)
def api(path):
    req=urllib.request.Request('https://api.github.com'+path,headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'})
    with urllib.request.urlopen(req,timeout=30) as r: return json.load(r)
def validate_identity(run,jobs,pr,commit,attempt):
    need(run['event']=='pull_request' and run['status']=='completed' and run['conclusion']=='success','unsuccessful run')
    need(run['path']=='.github/workflows/pr-check.yml' and run['run_attempt']==attempt,'workflow/attempt mismatch')
    need(run['repository']['full_name']==REPO and run['head_repository']['full_name']==REPO,'fork')
    need(len(jobs)==1 and jobs[0]['name']=='Lint / Test / Debug Build' and jobs[0]['status']=='completed' and jobs[0]['conclusion']=='success','missing required check')
    need(pr['state']=='open' and not pr.get('merged_at') and pr['base']['ref']=='local','closed or wrong base')
    need(pr['head']['repo']['full_name']==REPO and pr['base']['repo']['full_name']==REPO and pr['head']['sha']==run['head_sha'],'fork or stale head')
    need([x['sha'] for x in commit['parents']]==[pr['base']['sha'],pr['head']['sha']],'stale merge candidate')
    need(all(SHA.fullmatch(s) for s in (commit['sha'],pr['base']['sha'],pr['head']['sha'])),'bad SHA')
def validate_manifest(m,run,pr,source,attempt):
    expected={'schema_version':1,'repository':REPO,'pr_number':pr['number'],'feature_head_sha':pr['head']['sha'],'base_sha':pr['base']['sha'],'source_sha':source,'check_run_id':run['id'],'check_run_attempt':attempt}
    for k,v in expected.items(): need(m.get(k)==v,'manifest mismatch '+k)
    apks=m.get('apks'); need(isinstance(apks,list) and len(apks)==2 and {x.get('variant') for x in apks}=={'debug','release'},'missing APK')
    names=set()
    for a in apks:
        n=a.get('file_name',''); need(isinstance(n,str) and re.fullmatch(r'[A-Za-z0-9._+-]+\.apk',n) and 'qa-'+source[:7] in n and n not in names,'unsafe filename'); names.add(n)
        need(type(a.get('bytes')) is int and 0<a['bytes']<=LIMIT and HASH.fullmatch(a.get('sha256','')),'bad APK size/digest')
def unpack(archive,dest):
    with zipfile.ZipFile(archive) as z:
        infos=z.infolist(); names=[i.filename for i in infos]
        need(len(names)==len(set(names))==4 and {'manifest.json','REVIEW-CONTEXT.md'}<=set(names),'missing or duplicate entries')
        need(all(not i.is_dir() and '/' not in i.filename and '\\' not in i.filename and not stat.S_ISLNK(i.external_attr>>16) and i.file_size<=LIMIT for i in infos),'unsafe path or size')
        need(sum(i.file_size for i in infos)<=2*LIMIT+2*1024*1024 and z.getinfo('manifest.json').file_size<=65536 and z.getinfo('REVIEW-CONTEXT.md').file_size<=1048576,'oversize archive')
        dest.mkdir(parents=True,exist_ok=True)
        for i in infos:
            with z.open(i) as src,(dest/i.filename).open('wb') as out:
                while chunk:=src.read(1048576): out.write(chunk)
def verify_files(m,dest):
    need({p.name for p in dest.iterdir()}=={'manifest.json','REVIEW-CONTEXT.md'}|{a['file_name'] for a in m['apks']},'extra files')
    for a in m['apks']:
        p=dest/a['file_name']; need(p.stat().st_size==a['bytes'] and hashlib.sha256(p.read_bytes()).hexdigest()==a['sha256'],'unsigned digest mismatch')
def get_pr(run):
    refs=run.get('pull_requests') or []
    if refs:
        need(len(refs)==1,'ambiguous PR'); number=refs[0]['number']
    else:
        head=urllib.parse.quote('bianwuya:'+run['head_branch'],safe=''); matches=[]
        for page in range(1,11):
            batch=api(f'/repos/{REPO}/pulls?state=open&base=local&head={head}&per_page=100&page={page}')
            matches += [p for p in batch if p['head']['sha']==run['head_sha']]
            if len(batch)<100: break
        else: raise ValueError('too many PRs')
        need(len(matches)==1,'cannot uniquely resolve PR'); number=matches[0]['number']
    return api(f'/repos/{REPO}/pulls/{number}')

def build_input(path):
    """Automatic QA cannot approve a PR's own changed check/build entry points."""
    return (path in ('gradlew', 'gradlew.bat', 'gradle.properties', '.github/workflows/pr-check.yml')
            or path.endswith(('.gradle', '.gradle.kts'))
            or path.startswith(('gradle/', 'buildSrc/', 'build-logic/', '.github/actions/', '.github/scripts/')))

def validate_build_inputs(base_tree, source_tree):
    need(not base_tree.get('truncated') and not source_tree.get('truncated'), 'incomplete build-input tree')
    def relevant(tree):
        return {x['path']: (x['mode'], x['type'], x['sha']) for x in tree['tree'] if build_input(x['path'])}
    need(relevant(base_tree) == relevant(source_tree),
         'PR changed check/build entry points; review and integrate that infrastructure separately before automatic signing')

def identity(run_id,attempt,source=None):
    run=api(f'/repos/{REPO}/actions/runs/{run_id}'); need(run['id']==run_id,'run mismatch'); pr=get_pr(run)
    jobs=api(f'/repos/{REPO}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100'); need(jobs['total_count']==len(jobs['jobs'])==1,'incomplete jobs')
    if source is None: source=api(f'/repos/{REPO}/git/ref/pull/{pr["number"]}/merge')['object']['sha']
    need(SHA.fullmatch(source),'bad source'); commit=api(f'/repos/{REPO}/git/commits/{source}'); need(commit['sha']==source,'commit mismatch'); validate_identity(run,jobs['jobs'],pr,commit,attempt)
    # Compare execution definitions, not merely the job name. Tests and application
    # sources remain PR code; CI evidence is still not a substitute for code review.
    base_tree=api(f'/repos/{REPO}/git/trees/{pr["base"]["sha"]}?recursive=1')
    source_tree=api(f'/repos/{REPO}/git/trees/{source}?recursive=1')
    validate_build_inputs(base_tree, source_tree)
    return run,pr,source
def download(url,target):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,req,fp,code,msg,headers,newurl): return None
    req=urllib.request.Request(url,headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'})
    try: urllib.request.build_opener(NoRedirect).open(req,timeout=30)
    except urllib.error.HTTPError as e:
        need(e.code in (301,302,303,307,308),'artifact download failed'); location=e.headers['Location']; need(urllib.parse.urlparse(location).scheme=='https','invalid redirect')
    else: raise ValueError('expected redirect')
    with urllib.request.urlopen(location,timeout=60) as r,open(target,'wb') as out:
        total=0
        while chunk:=r.read(1048576):
            total+=len(chunk); need(total<=2*LIMIT+2*1024*1024,'download too large'); out.write(chunk)
def prevent_duplicate(run_id):
    name=urllib.parse.quote('qa-proof-'+str(run_id),safe='')
    artifacts=api(f'/repos/{REPO}/actions/artifacts?name={name}&per_page=100')
    need(artifacts['total_count']==len(artifacts['artifacts']),'too many previous proofs')
    for artifact in artifacts['artifacts']:
        if artifact['expired'] or artifact['name']!='qa-proof-'+str(run_id): continue
        prior=api(f"/repos/{REPO}/actions/runs/{artifact['workflow_run']['id']}")
        need(not (prior['path']=='.github/workflows/device-test-apk.yml' and prior['conclusion']=='success'), 'already signed by successful run')

def prepare(run_id,attempt,dest,archive):
    prevent_duplicate(run_id)
    run,pr,source=identity(run_id,attempt); arts=api(f'/repos/{REPO}/actions/runs/{run_id}/artifacts?per_page=100'); need(arts['total_count']==len(arts['artifacts']),'incomplete artifact list')
    found=[x for x in arts['artifacts'] if x['name']=='pr-qa-input' and not x['expired']]; need(len(found)==1,'missing or duplicate input')
    download(f'https://api.github.com/repos/{REPO}/actions/artifacts/{found[0]["id"]}/zip',archive); unpack(archive,dest)
    m=json.loads((dest/'manifest.json').read_text()); validate_manifest(m,run,pr,source,attempt); verify_files(m,dest)
def recheck(run_id,attempt,dest):
    m=json.loads((dest/'manifest.json').read_text()); run,pr,source=identity(run_id,attempt,m['source_sha']); validate_manifest(m,run,pr,source,attempt); verify_files(m,dest)
def receipt(run_id,attempt,dest,signed,proof):
    import shutil,subprocess
    m=json.loads((dest/'manifest.json').read_text())
    tools=sorted((Path(os.environ['ANDROID_HOME'])/'build-tools').iterdir(), key=lambda p: tuple(int(x) for x in re.findall(r'\d+',p.name)))
    aapt=tools[-1]/'aapt'; entries=[]
    for a in m['apks']:
        p=signed/a['file_name']; need(p.is_file() and 0<p.stat().st_size<=LIMIT,'signed APK missing or too large')
        output=subprocess.check_output([str(aapt),'dump','badging',str(p)],text=True)
        line=next(x for x in output.splitlines() if x.startswith('package: '))
        pkg=re.search(r"name='([^']+)'",line); ver=re.search(r"versionName='([^']+)'",line)
        need(pkg and pkg.group(1)=='io.github.mangi.eta' and ver and ver.group(1).endswith('+qa-'+m['source_sha'][:7]),'wrong package or version')
        need(p.name=='Eta-v'+ver.group(1)+'.apk' and ('-debug+' in ver.group(1))==(a['variant']=='debug'),'variant/version filename mismatch')
        entry={'variant':a['variant'],'file_name':a['file_name'],'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'certificate_sha256':'a7a92cca03f7d7390ab74001c97efbfe4ff4289b0dc4ffa21d6ebc3cee1c81a8','version_name':ver.group(1)}; entries.append(entry)
    r={k:m[k] for k in ('schema_version','repository','pr_number','feature_head_sha','base_sha','source_sha','check_run_id','check_run_attempt')}
    r.update(channel='pr-qa',sign_run_id=int(os.environ['GITHUB_RUN_ID']),sign_run_attempt=int(os.environ['GITHUB_RUN_ATTEMPT']),apks=entries,pr_url=f"https://github.com/{REPO}/pull/{m['pr_number']}",check_run_url=f"https://github.com/{REPO}/actions/runs/{run_id}",sign_run_url=f"https://github.com/{REPO}/actions/runs/{os.environ['GITHUB_RUN_ID']}",context_file='REVIEW-CONTEXT.md')
    proof.mkdir(parents=True,exist_ok=True); (proof/'qa-receipt.json').write_text(json.dumps(r,indent=2)+'\n'); shutil.copyfile(dest/'REVIEW-CONTEXT.md',proof/'REVIEW-CONTEXT.md')
    with open(os.environ['GITHUB_OUTPUT'],'a') as out:
        for e in entries: out.write(e['variant']+'_name='+e['file_name']+'\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out:
        out.write(f"## PR QA delivery\n\n[PR]({r['pr_url']}) · [Check]({r['check_run_url']}) · [Signing]({r['sign_run_url']})\n\nSource `{m['source_sha']}`; not installed or user-accepted.\n")

if __name__=='__main__':
    import argparse,sys
    p=argparse.ArgumentParser(); p.add_argument('command',choices=('prepare','recheck','receipt')); p.add_argument('--run',type=int,required=True); p.add_argument('--attempt',type=int,required=True); p.add_argument('--directory',type=Path,required=True); p.add_argument('--archive',default='/tmp/qa-input.zip'); p.add_argument('--signed',type=Path); p.add_argument('--proof',type=Path); a=p.parse_args()
    try:
        need(os.environ['GITHUB_REPOSITORY']==REPO,'wrong repository')
        if a.command=='prepare': prepare(a.run,a.attempt,a.directory,a.archive)
        elif a.command=='recheck': recheck(a.run,a.attempt,a.directory)
        else: receipt(a.run,a.attempt,a.directory,a.signed,a.proof)
    except Exception as e: print('QA validation failed:',e,file=sys.stderr); sys.exit(1)
