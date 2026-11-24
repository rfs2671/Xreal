import sys, itertools, numpy as np, io, contextlib
sys.path.insert(0,'/c/uvio/handup')
import pinch_lab as pl
files=sys.argv[1:]
def stats(machine):
    buf=io.StringIO()
    with contextlib.redirect_stdout(buf): pl.report('x',machine,files)
    s=buf.getvalue(); import re
    m=re.search(r'onsets\s+(\d+) flicker<120ms\s+(\d+) unsupported\s+(\d+) \| open-hand samples pinching (\d+)/\d+ \| onset vs 40mm-crossing: median (\S+) ms P90 (\S+)',s)
    return tuple(float(x) for x in m.groups())
print('old',stats(pl.Old))
for drop,nmax,lead,pn in itertools.product((0.2,0.3,0.4),(0.55,0.65,0.75),(0.03,0.045),(0.46,0.38,0.30)):
    class M(pl.New): pass
    M.DROP2=drop;M.NMAX=nmax;M.LEAD=lead;M.PRESS_N=pn
    print(f"drop{drop} nmax{nmax} lead{lead} proj{pn}:",stats(M))
