"""Independent fixture/reference checks for the portable native patcher."""
from pathlib import Path
import ctypes,gzip,json,struct,subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[3]
PATCH=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'analysis_lk/old_lk_compat'))
from dtb_compat import parse_fdt,vendor_dtb,LibFdt,dt_table
from build_vendor_compat_rmvr import parse_vendor
EXE=PATCH/'build/host/vendorpatch_cli.exe'
if not EXE.exists():EXE=PATCH/'build/host/vendorpatch_cli'
def avb_blob(b):
 assert b[-64:-60]==b'AVBf'
 off,size=struct.unpack_from('>QQ',b,len(b)-64+20)
 return b[off:off+size]
def align(n,a=4096):return (n+a-1)//a*a
def invoke(src,out,scp,capacity,success=True):
 p=subprocess.run([str(EXE),str(src),str(out),str(scp),str(capacity)],capture_output=True,text=True)
 r=json.loads(p.stdout);assert r['ok']==success,(r,p.stderr)
 assert r['schema']==2 and 'outputSha256'not in r
 if success:
  assert p.returncode==0 and out.stat().st_size==capacity
  assert r['structureValidated']and r['outputPath']==str(out)and r['outputSize']==capacity
  assert 'digestStale'not in r['avb']and 'signedMetadataPreserved'not in r['avb']
  assert r['avb']['metadataStatus']==('preserved'if r['avb']['present']else'absent')
  assert r['avb']['verificationStatus']==('unverified'if r['avb']['present']else'not_present')
  assert r['avb']['imageStatus']==('modified'if r['changed']else'unchanged')and not r['avb']['resigned']
  blobs=list(image_dtbs(out.read_bytes()));assert len(blobs)==len(r['dtbs']) and blobs
  for blob,detail in zip(blobs,r['dtbs']):
   value=parse_fdt(blob)['/chosen']['properties']['bt_log']['data']
   assert len(value)>=4 and value[0]==0x11 and value[1]&0xf0==0x10
   assert detail['btLogCompatible'] is True and detail['btLogLength']==len(value) and detail['btLogPrefix']==value[:4].hex()
   assert type(detail['btLogAdded']) is bool and type(detail['btLogChanged']) is bool
 else:assert p.returncode!=0 and not out.exists()
 return r
def cpio():
 result=bytearray()
 for name,data in [('lib/modules/vr.ko',b'vr-payload'),('lib/modules/vklp.ko',b'vklp-payload'),
  ('lib/modules/vklp_smaps_rollup.ko',b'preserved-orphan'),('lib/modules/keep.ko',b'KEEP'),
  ('lib/modules/modules.load',b'vr.ko\nvklp.ko\nvklp_smaps_rollup.ko\nkeep.ko\n'),
  ('lib/modules/modules.load.future',b'vklp_smaps_rollup.ko\nkeep.ko\n'),
  ('lib/modules/modules.dep',b'keep.ko: vr.ko vklp.ko\nvr.ko:\n'),('TRAILER!!!',b'')]:
  f=[1,0o100644,0,0,1,0,len(data),0,0,0,0,len(name)+1,0]
  result+=b'070701'+b''.join(f'{v:08x}'.encode()for v in f)+name.encode()+b'\0'
  result+=bytes(-len(result)%4);result+=data;result+=bytes(-len(result)%4)
 return bytes(result)+bytes(64)
def legacy(raw):
 import lz4.block
 b=lz4.block.compress(raw,mode='high_compression',compression=9,store_size=False)
 return bytes.fromhex('02214c18')+struct.pack('<I',len(b))+b
def table(dtb):return struct.pack('>8I',0xd7b7ab1e,64+len(dtb),32,32,1,32,4096,0)+struct.pack('>8I',len(dtb),64,1,2,3,4,5,6)+dtb

def table_many(blobs):
 result=bytearray(32+32*len(blobs))
 for i,blob in enumerate(blobs):
  result+=bytes(-len(result)%4);off=len(result);result+=blob
  struct.pack_into('>8I',result,32+i*32,len(blob),off,i+1,2,3,4,5,6)
 struct.pack_into('>8I',result,0,0xd7b7ab1e,len(result),32,32,len(blobs),32,4096,0)
 return bytes(result)

def parse_vendor_fixture_dtb(image):
 page=struct.unpack_from('<I',image,12)[0];rs=struct.unpack_from('<I',image,24)[0];size=struct.unpack_from('<I',image,2100)[0];off=page+align(rs,page)
 return image[off:off+size]

def image_dtbs(image):
 def sequence(region):
  if region[:4]==bytes.fromhex('d7b7ab1e'):
   for entry in dt_table(region):yield from sequence(entry['data'])
   return
  pos=0
  while pos<len(region) and region[pos:].strip(b'\0'):
   assert region[pos:pos+4]==bytes.fromhex('d00dfeed')
   n=struct.unpack_from('>I',region,pos+4)[0];yield region[pos:pos+n];pos+=align(n,4)
 yield from sequence(parse_vendor_fixture_dtb(image))
def fixture(version,ram,dtb,capacity=4*1024*1024):
 b=bytearray(capacity);b[:8]=b'VNDRBOOT';struct.pack_into('<II',b,8,version,4096);struct.pack_into('<I',b,24,len(ram));struct.pack_into('<II',b,2096,2128 if version==4 else 2112,len(dtb));b[4096:4096+len(ram)]=ram
 d=4096+align(len(ram));b[d:d+len(dtb)]=dtb
 if version==4:
  t=d+align(len(dtb));struct.pack_into('<4I',b,2112,108,1,108,0);struct.pack_into('<III',b,t,len(ram),0,1);b[t+12:t+20]=b'platform'
 return bytes(b)
def mutate_property(lib,dtb,path,key,value):
 dll=lib.dll;dll.fdt_setprop.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_void_p,ctypes.c_int];dll.fdt_setprop.restype=ctypes.c_int
 dll.fdt_delprop.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_char_p];dll.fdt_delprop.restype=ctypes.c_int
 src=ctypes.create_string_buffer(dtb,len(dtb));buf=ctypes.create_string_buffer(len(dtb)+65536)
 assert dll.fdt_open_into(src,buf,len(buf))==0
 node=dll.fdt_path_offset(buf,path.encode());assert node>=0
 if value is None:assert dll.fdt_delprop(buf,node,key.encode())==0
 else:assert dll.fdt_setprop(buf,node,key.encode(),value,len(value))==0
 assert dll.fdt_pack(buf)==0
 return buf.raw[:struct.unpack_from('>I',buf.raw,4)[0]]
def main():
 reports=[];stock=ROOT/'extracted_20261010_234555';source=(stock/'vendor_boot.img').read_bytes();scp=stock/'scp.img'
 tmpdir=PATCH/'build/test';tmpdir.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(dir=tmpdir)as td:
  td=Path(td);out=td/'real.img';r=invoke(stock/'vendor_boot.img',out,scp,len(source));reports.append({'case':'real_stock','report':r})
  out2=td/'real2.img';again=invoke(out,out2,scp,len(source));assert not again['changed']and out.read_bytes()==out2.read_bytes();reports.append({'case':'real_idempotence','passed':True})
  before=parse_vendor(source);after=parse_vendor(out.read_bytes())
  # Frozen, previously reviewed V2 output supplies the same complete fragment reference.
  # Only DTB bt_log is added in 2.1; no reference helper digest work is performed.
  baseline=parse_vendor((ROOT/'ota-helper/patcher/build/test/patched_vendor_preserved_helper.img').read_bytes())
  for a,b,reference in zip(before['entries'],after['entries'],baseline['entries']):
   assert reference['data']==b['data'] and a['record'][8:]==b['record'][8:]
  assert before['config']==after['config'];assert avb_blob(source)==avb_blob(out.read_bytes())
  _,actual=vendor_dtb(out.read_bytes());current=parse_fdt(actual)
  _,tested=vendor_dtb((ROOT/'analysis_lk/scp_fix_v3/vendor_boot_originos7_oldlk_status_v3_diagnostic.img').read_bytes());ref=parse_fdt(tested)
  def props(n):return {p:{k:v['data']for k,v in node['properties'].items()}for p,node in n.items()}
  expected=props(ref)
  for key in list(expected['/chosen']):
   if key.startswith('scp-compat-'):del expected['/chosen'][key]
  expected['/chosen']['bt_log']=bytes.fromhex('11100000')
  assert props(current)==expected
  assert current['/soc/scp-lk-compat@1d000000']['properties']['scp-dram-size']['data']==struct.pack('>I',0x300000)
  assert current['/soc/scp@1d000000']['properties']['scp-dram-size']['data']==struct.pack('>I',0x1d0000)
  assert r['dtbs'][0]['helperDramSize']==0x300000 and r['dtbs'][0]['realDramSize']==0x1d0000
  assert r['dtbs'][0]['btLogAdded'] and r['dtbs'][0]['btLogChanged']
  lib=LibFdt()
  for entry in dt_table((stock/'dtbo.img').read_bytes()):lib.hook_paths(lib.merge(actual,entry['data']),True)
  reports.append({'case':'reference_V3_semantics_all_fragments_AVB_and_3_overlays','passed':True})
  _,base=vendor_dtb(source);raw=cpio()
  bt_values=[('compatible4',bytes.fromhex('11100000'),bytes.fromhex('11100000')),
   ('compatible7_with_tail',bytes.fromhex('111e80ff00aa55'),bytes.fromhex('111e80ff00aa55')),
   ('normalize4',bytes.fromhex('ffaf1234'),bytes.fromhex('111f1234')),
   ('normalize5_preserve_byte2_3_tail',bytes.fromhex('2297aabbcc'),bytes.fromhex('1117aabbcc'))]
  bt_values += [(f'low_nibble_{low:x}',bytes([0x7f,0xb0+low,0x80,0xff])+b'\0TAIL\xff',bytes([0x11,0x10+low,0x80,0xff])+b'\0TAIL\xff')for low in range(16)]
  for i,(name,value,expected_bt)in enumerate(bt_values):
   changed_base=mutate_property(lib,base,'/chosen','bt_log',value)
   inp=td/f'bt{i}.img';inp.write_bytes(fixture(3,gzip.compress(raw,mtime=0),changed_base));dest=td/f'bt{i}.out'
   ans=invoke(inp,dest,scp,inp.stat().st_size);blob=list(image_dtbs(dest.read_bytes()))[0];tree=parse_fdt(blob)
   assert tree['/chosen']['properties']['bt_log']['data']==expected_bt
   assert not ans['dtbs'][0]['btLogAdded']and ans['dtbs'][0]['btLogChanged']==(value!=expected_bt)
   dest2=td/f'bt{i}.out2';second=invoke(dest,dest2,scp,dest.stat().st_size);assert not second['changed']and not second['dtbs'][0]['btLogChanged']and dest.read_bytes()==dest2.read_bytes()
   reports.append({'case':'bt_log_'+name+'_tail_and_idempotence','passed':True})
  multi=td/'bt-multiple.img';multi.write_bytes(fixture(4,legacy(raw),table_many([base,mutate_property(lib,base,'/chosen','bt_log',bytes.fromhex('55a7aabbcc'))])))
  multi_out=td/'bt-multiple.out';multi_report=invoke(multi,multi_out,scp,multi.stat().st_size)
  assert [x['btLogPrefix']for x in multi_report['dtbs']]==['11100000','1117aabb']and [x['btLogLength']for x in multi_report['dtbs']]==[4,5]
  original_entries=dt_table(parse_vendor_fixture_dtb(multi.read_bytes()));new_entries=dt_table(parse_vendor_fixture_dtb(multi_out.read_bytes()))
  assert [x['values'][2:]for x in original_entries]==[x['values'][2:]for x in new_entries]
  reports.append({'case':'bt_log_all_DT_table_entries_preserve_metadata','passed':True})
  formats=[(3,'gzip',gzip.compress(raw,mtime=0),base),(3,'raw-cpio',raw,table(base)),
   (4,'lz4',legacy(raw),table(base)),(4,'gzip-concat-dtb',gzip.compress(raw,mtime=0),base+bytes(-len(base)%4)+base)]
  for i,(version,name,ram,dt)in enumerate(formats):
   inp=td/f'format{i}.img';inp.write_bytes(fixture(version,ram,dt));dest=td/f'format{i}.out';ans=invoke(inp,dest,scp,inp.stat().st_size);assert ans['fragmentCount']==1
   dest2=td/f'format{i}.out2';ans2=invoke(dest,dest2,scp,dest.stat().st_size);assert not ans2['changed']and dest.read_bytes()==dest2.read_bytes()
   reports.append({'case':f'version{version}_{name}_roundtrip_idempotence','passed':True})
  good=fixture(3,gzip.compress(raw,mtime=0),base)
  bads=[];unknown=bytearray(good);unknown[4096:4100]=b'????';bads.append(('unknown_compression',bytes(unknown),scp,len(good)))
  corrupt=bytearray(good);g=gzip.compress(raw,mtime=0);corrupt[4096+len(g)-8]^=1;bads.append(('gzip_crc',bytes(corrupt),scp,len(good)))
  opaque=bytearray(good);opaque[-128]=1;bads.append(('opaque_trailer',bytes(opaque),scp,len(good)))
  short=bytearray(good);struct.pack_into('<I',short,2100,0xffffffff);bads.append(('bad_dtb_bounds',bytes(short),scp,len(good)))
  bads.append(('capacity_too_small',good,scp,len(good)-1))
  for size in range(4):
   bad_dtb=mutate_property(lib,base,'/chosen','bt_log',bytes(range(size)))
   malformed=fixture(3,gzip.compress(raw,mtime=0),bad_dtb);bads.append((f'bt_log_existing_length_{size}',malformed,scp,len(malformed)))
  bad_multi=fixture(4,legacy(raw),table_many([base,mutate_property(lib,base,'/chosen','bt_log',b'\x11\x10')]))
  bads.append(('bt_log_short_second_DT_table_entry',bad_multi,scp,len(bad_multi)))
  missing=td/'missing-scp.bin';missing.write_bytes(bytes(1024));bads.append(('missing_scp_dram',good,missing,len(good)))
  dram_header=(scp.read_bytes())[0x3e55f0:0x3e55f0+512+0x1ce2ca];amb=td/'ambiguous-scp.bin';amb.write_bytes(dram_header+bytes(-len(dram_header)%4)+dram_header);bads.append(('ambiguous_scp_dram',good,amb,len(good)))
  helper='/soc/scp-lk-compat@1d000000'
  mutations=[('helper_dram_missing','scp-dram-size',None),('helper_dram_short','scp-dram-size',b'\0\0'),
   ('helper_dram_zero','scp-dram-size',bytes(4)),('helper_dram_insufficient','scp-dram-size',struct.pack('>I',0x10000)),
   ('helper_dram_over_limit','scp-dram-size',struct.pack('>I',0x40010000)),('helper_dram_unaligned','scp-dram-size',struct.pack('>I',0x300001)),
   ('helper_bad_status','status',b'okay\0'),('helper_missing_interrupts','interrupts',None),
   ('helper_register_mismatch','reg',bytes(4)),('helper_phandle_conflict','phandle',struct.pack('>I',1))]
  for name,key,value in mutations:
   malformed=fixture(3,gzip.compress(raw,mtime=0),mutate_property(lib,actual,helper,key,value))
   bads.append((name,malformed,scp,len(malformed)))
  for i,(name,data,firmware,cap)in enumerate(bads):
   inp=td/f'bad{i}.img';inp.write_bytes(data);ans=invoke(inp,td/f'bad{i}.out',firmware,cap,False);assert inp.read_bytes()==data
   if name.startswith('bt_log_'):assert '/chosen/bt_log' in ans['error']and 'at least 4 bytes' in ans['error']
   reports.append({'case':name,'passed':True,'error':ans['error']})
  occupied=td/'occupied';occupied.write_bytes(b'UNTOUCHED');p=subprocess.run([str(EXE),str(stock/'vendor_boot.img'),str(occupied),str(scp),str(len(source))],capture_output=True,text=True);assert not json.loads(p.stdout)['ok']and occupied.read_bytes()==b'UNTOUCHED';reports.append({'case':'existing_output_untouched','passed':True})
 report={'passed':True,'cases':reports,'device_access':False,'original_images_unchanged':(stock/'vendor_boot.img').read_bytes()==source,'runtime_checksum_validation':False,'development_byte_comparisons_only':True}
 (PATCH/'build/host_validation.json').write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps({'passed':True,'cases':len(reports),'report':str(PATCH/'build/host_validation.json')}))
if __name__=='__main__':main()
