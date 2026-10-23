#!/usr/bin/env python3
import json,pathlib,subprocess,tempfile,zipfile
base=pathlib.Path(__file__).resolve().parents[2]
jar=(base/'runtime/releases/current/backend.jar').resolve()
source=base/'src/backend/yudao-server/src/main/java/cn/iocoder/yudao/server/print/file/PdfInspection.java'
check=base/'src/backend/yudao-server/src/test/java/cn/iocoder/yudao/server/print/file/PdfInspectionChecks.java'
with tempfile.TemporaryDirectory(prefix='pdf-check-') as directory:
    root=pathlib.Path(directory);libs=[]
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            name=pathlib.PurePosixPath(entry).name
            if entry.startswith('BOOT-INF/lib/') and name.startswith(('pdfbox-','fontbox-','commons-logging-')) and name.endswith('.jar'):
                target=root/name;target.write_bytes(archive.read(entry));libs.append(str(target))
    cp=':'.join(libs);classes=root/'classes';classes.mkdir()
    compile=subprocess.run(['javac','-J-Xmx128m','-cp',cp,'-d',str(classes),str(source),str(check)],capture_output=True,text=True)
    if compile.returncode:
        print('PDF_CHECK_COMPILE_FAILED');raise SystemExit(1)
    run=subprocess.run(['java','-Xmx128m','-cp',str(classes)+':'+cp,'cn.iocoder.yudao.server.print.file.PdfInspectionChecks',str(root)],capture_output=True,text=True)
    passed=run.returncode==0 and 'pdf_parser_checks_passed=8' in run.stdout
    report={'passed':passed,'checks':8 if passed else 0,'source':'synthetic PDFs generated with locked PDFBox dependency','http_upload_tested':False,'hard_parser_timeout_tested':False}
    if not passed:report['failure_type']='parser_check_failure'
report['temporary_files_removed']=not root.exists()
(base/'evidence/acceptance/pdf-parser-checks.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report));raise SystemExit(0 if passed else 1)
