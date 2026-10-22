"""Build the same native core as a Windows/Linux CLI using local clang."""
from pathlib import Path
import concurrent.futures,shutil,subprocess,sys
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'build/host';OUT.mkdir(parents=True,exist_ok=True)
cc=shutil.which('clang');cxx=shutil.which('clang++')
assert cc and cxx,'clang and clang++ are required'
sources=list((ROOT/'src').glob('*.cpp'))
sources=[p for p in sources if p.name!='jni.cpp']
sources += [ROOT/'third_party/lz4'/n for n in ['lz4.c','lz4hc.c']]
sources += [ROOT/'third_party/zlib'/n for n in ['adler32.c','crc32.c','deflate.c','inflate.c','inffast.c','inftrees.c','trees.c','zutil.c']]
sources += list((ROOT/'third_party/libfdt').glob('*.c'))
includes=[ROOT/'include',ROOT/'src',ROOT/'third_party/libfdt',ROOT/'third_party/lz4',ROOT/'third_party/zlib']
def build(p):
 obj=OUT/('_'.join(p.relative_to(ROOT).parts)+'.o')
 cmd=[cxx if p.suffix=='.cpp' else cc,'-O2','-DZ_SOLO','-c',str(p),'-o',str(obj)]
 if p.suffix=='.cpp':cmd += ['-std=c++17','-Wall','-Wextra','-Werror']
 for d in includes:cmd += ['-I',str(d)]
 subprocess.run(cmd,check=True,capture_output=True)
 return obj
try:
 with concurrent.futures.ThreadPoolExecutor(max_workers=4)as pool:objects=list(pool.map(build,sources))
 exe=OUT/('vendorpatch_cli.exe'if sys.platform=='win32'else'vendorpatch_cli')
 subprocess.run([cxx,*map(str,objects),'-o',str(exe)],check=True)
 print(exe)
except subprocess.CalledProcessError as e:
 print((e.stderr or b'').decode(errors='replace'));raise
