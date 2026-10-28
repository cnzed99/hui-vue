const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),vm=require('node:vm'),ts=require('typescript')
const root=path.resolve(__dirname,'..'), model={}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root,'src/print-admin/library/categoryModel.ts'),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,{exports:model})
assert.equal(model.categoryNameError('分类😀'),'')
assert.notEqual(model.categoryNameError(' '),'')
assert.notEqual(model.categoryNameError('分'.repeat(101)),'')
const category={id:'c632bb5e-a67a-4b99-9195-66e3da8e33fb',name:'分类',version:'9007199254740993',fileCount:null,sort:null}
assert.equal(model.parseCategory(category).version,category.version)
assert.throws(()=>model.parseCategory({...category,fileCount:0}))
const {parse,compileScript,compileTemplate}=require('vue/compiler-sfc')
for(const file of ['CategoryForm.vue','LibraryCategories.vue']){
 const filename=path.join(root,'src/print-admin/library',file), parsed=parse(fs.readFileSync(filename,'utf8'),{filename})
 assert.deepEqual(parsed.errors,[]);compileScript(parsed.descriptor,{id:file});assert.deepEqual(compileTemplate({source:parsed.descriptor.template.content,filename,id:file}).errors,[])
}
console.log('P15_FRONTEND_SOURCE_PASS modelChecks=5 sfcCount=2 HTTP_BROWSER_NOT_RUN')
