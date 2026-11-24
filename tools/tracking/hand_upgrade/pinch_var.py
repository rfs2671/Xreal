import sys, io, contextlib, re
sys.path.insert(0,'/c/uvio/handup')
import pinch_lab as pl
files=sys.argv[1:]
def stats(machine):
    buf=io.StringIO()
    with contextlib.redirect_stdout(buf): pl.report('x',machine,files)
    s=buf.getvalue(); m=re.search(r'onsets\s+(\d+) flicker<120ms\s+(\d+) unsupported\s+(\d+) \| open-hand samples pinching (\d+)/\d+ \| onset vs 40mm-crossing: median (\S+) ms P90 (\S+)',s)
    return tuple(float(x) for x in m.groups())
print('variant                onsets flick unsup openPinching onsetMed onsetP90')
print('old                   ',stats(pl.Old))
class RelOnly(pl.New):
    NMAX=-1       # never early
print('fast release only     ',stats(RelOnly))
class EarlyOnly(pl.New):
    OPEN_V=1e9
print('early press only      ',stats(EarlyOnly))
class Guard(pl.New):
    NMAX=-1; OPEN_V=1e9
print('plausibility guard only',stats(Guard))
class Early2(pl.New):
    OPEN_V=1e9; DROP2=0.3; NMAX=0.6; LEAD=0.045; PRESS_N=0.40
print('early strict          ',stats(Early2))
