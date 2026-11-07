import importlib.util,json,tempfile,unittest,zipfile,hashlib
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'scripts/device_qa.py'
spec=importlib.util.spec_from_file_location('qa',p); qa=importlib.util.module_from_spec(spec); spec.loader.exec_module(qa)
class QA(unittest.TestCase):
    def setUp(self):
        self.head='a'*40; self.base='b'*40; self.source='c'*40; self.repo={'full_name':qa.REPO}
        self.run={'event':'pull_request','status':'completed','conclusion':'success','path':'.github/workflows/pr-check.yml','run_attempt':1,'repository':self.repo,'head_repository':self.repo,'head_sha':self.head,'id':123}
        self.pr={'number':2,'state':'open','merged_at':None,'base':{'ref':'local','sha':self.base,'repo':self.repo},'head':{'sha':self.head,'repo':self.repo}}
        self.commit={'sha':self.source,'parents':[{'sha':self.base},{'sha':self.head}]}
        self.jobs=[{'name':'Lint / Test / Debug Build','status':'completed','conclusion':'success'}]
        self.m={'schema_version':1,'repository':qa.REPO,'pr_number':2,'feature_head_sha':self.head,'base_sha':self.base,'source_sha':self.source,'check_run_id':123,'check_run_attempt':1,'apks':[{'variant':v,'file_name':v+'-qa-ccccccc.apk','bytes':1,'sha256':hashlib.sha256(b'x').hexdigest()} for v in ('debug','release')]}
    def test_valid(self): qa.validate_identity(self.run,self.jobs,self.pr,self.commit,1); qa.validate_manifest(self.m,self.run,self.pr,self.source,1)
    def test_stale_head(self):
        self.pr['head']['sha']='d'*40
        with self.assertRaises(ValueError): qa.validate_identity(self.run,self.jobs,self.pr,self.commit,1)
    def test_stale_base(self):
        self.pr['base']['sha']='d'*40
        with self.assertRaises(ValueError): qa.validate_identity(self.run,self.jobs,self.pr,self.commit,1)
    def test_source_vs_head(self):
        self.m['source_sha']=self.head
        with self.assertRaises(ValueError): qa.validate_manifest(self.m,self.run,self.pr,self.source,1)
    def test_job_missing(self):
        with self.assertRaises(ValueError): qa.validate_identity(self.run,[],self.pr,self.commit,1)
    def test_job_failed(self):
        self.jobs[0]['conclusion']='failure'
        with self.assertRaises(ValueError): qa.validate_identity(self.run,self.jobs,self.pr,self.commit,1)
    def test_fork(self):
        self.run['head_repository']={'full_name':'evil/Eta'}
        with self.assertRaises(ValueError): qa.validate_identity(self.run,self.jobs,self.pr,self.commit,1)
    def test_attempt(self):
        with self.assertRaises(ValueError): qa.validate_identity(self.run,self.jobs,self.pr,self.commit,2)
    def test_closed(self):
        self.pr['state']='closed'
        with self.assertRaises(ValueError): qa.validate_identity(self.run,self.jobs,self.pr,self.commit,1)
    def test_traversal(self):
        self.m['apks'][0]['file_name']='../qa-ccccccc.apk'
        with self.assertRaises(ValueError): qa.validate_manifest(self.m,self.run,self.pr,self.source,1)
    def test_zip_traversal(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.zip'
            with zipfile.ZipFile(p,'w') as z:
                for n in ('manifest.json','REVIEW-CONTEXT.md','../bad.apk','x.apk'): z.writestr(n,'x')
            with self.assertRaises(ValueError): qa.unpack(p,Path(d)/'out')
    def test_digest(self):
        with tempfile.TemporaryDirectory() as d:
            dest=Path(d)
            for a in self.m['apks']: (dest/a['file_name']).write_bytes(b'x')
            for name in ('manifest.json','REVIEW-CONTEXT.md'): (dest/name).write_text('x')
            qa.verify_files(self.m,dest)
            (dest/self.m['apks'][0]['file_name']).write_bytes(b'y')
            with self.assertRaises(ValueError): qa.verify_files(self.m,dest)
    def test_build_entry_changes_cannot_self_approve_signing(self):
        def tree(path, digest='1'*40):
            return {'truncated':False, 'tree':[{'path':path,'sha':digest,'type':'blob','mode':'100644'}]}
        for path in ('.github/workflows/pr-check.yml', '.github/actions/setup-build/action.yml',
                     'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'app/build.gradle.kts',
                     'settings.gradle.kts', 'buildSrc/src/main/kotlin/Plugin.kt'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                qa.validate_build_inputs(tree(path), tree(path, '2'*40))
        qa.validate_build_inputs(tree('app/src/main/kotlin/Feature.kt'),
                                 tree('app/src/main/kotlin/Feature.kt', '2'*40))
        with self.assertRaises(ValueError): qa.validate_build_inputs({'truncated':True}, tree('gradlew'))
    def test_receipt_uses_signed_digest_not_unsigned_digest(self):
        import os
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); dest=root/'input'; signed=root/'signed'; proof=root/'proof'
            dest.mkdir(); signed.mkdir(); (root/'sdk'/'build-tools'/'37.0.0').mkdir(parents=True)
            self.m['apks']=[dict(a, file_name='Eta-v3.4.0'+('-debug' if a['variant']=='debug' else '')+'+qa-ccccccc.apk') for a in self.m['apks']]
            (dest/'manifest.json').write_text(json.dumps(self.m)); (dest/'REVIEW-CONTEXT.md').write_text('PR2 -> PR24')
            for a in self.m['apks']: (signed/a['file_name']).write_bytes(b'signed-'+a['variant'].encode())
            def badging(command, **kwargs):
                name=Path(command[-1]).name
                return "package: name='io.github.mangi.eta' versionName='"+name[len('Eta-v'):-len('.apk')]+"'\n"
            env={'ANDROID_HOME':str(root/'sdk'),'GITHUB_RUN_ID':'200','GITHUB_RUN_ATTEMPT':'1',
                 'GITHUB_OUTPUT':str(root/'outputs'),'GITHUB_STEP_SUMMARY':str(root/'summary')}
            with patch.dict(os.environ,env), patch('subprocess.check_output',side_effect=badging):
                qa.receipt(123,1,dest,signed,proof)
            receipt=json.loads((proof/'qa-receipt.json').read_text())
            for a in receipt['apks']:
                actual=(signed/a['file_name']).read_bytes()
                self.assertEqual(hashlib.sha256(actual).hexdigest(),a['sha256'])
                self.assertNotEqual(self.m['apks'][0]['sha256'],a['sha256'])
            self.assertEqual(200,receipt['sign_run_id'])
            self.assertEqual(self.source,receipt['source_sha'])
    def test_workflow_contract(self):
        workflow=p.parents[1]/'workflows/pr-check.yml'
        text=workflow.read_text()
        self.assertIn('name: Lint / Test / Debug Build',text); self.assertIn('pull_request.number',text)
        self.assertIn(':app:assembleRelease',text); self.assertIn(':app:testDebugUnitTest',text)
        self.assertIn('python3 -m unittest discover -s .github/tests -v',text)
        sign=(p.parents[1]/'workflows/device-test-apk.yml').read_text()
        self.assertIn('workflow_run:',sign); self.assertIn('workflow_dispatch:',sign); self.assertIn('qa-sign-${{ github.event.workflow_run.id || inputs.check_run_id }}',sign)
if __name__=='__main__': unittest.main()
