import sys, io, contextlib, re
sys.path.insert(0,'/c/uvio/handup')
import pinch_lab as pl
files=sys.argv[1:]
def stats(machine):
    buf=io.StringIO()
    with contextlib.redirect_stdout(buf): pl.report('x',machine,files)
    s=buf.getvalue(); m=re.search(r'onsets\s+(\d+) flicker<120ms\s+(\d+) unsupported\s+(\d+) \| open-hand samples pinching (\d+)/\d+ \| onset vs 40mm-crossing: median (\S+) ms P90 (\S+)',s)
    return tuple(float(x) for x in m.groups())
print('variant                         onsets flick unsup openPinching onsetMed onsetP90')
class E2(pl.New): OPEN_V=1e9; DROP2=0.3; NMAX=0.6; LEAD=0.045; PRESS_N=0.40
print('early strict (shipped)          ',stats(E2))
for lock in (0.10,0.20,0.30):
    for ov in (1.5,2.5):
        class R(pl.Rel):
            DROP2=0.3; NMAX=0.6; LEAD=0.045; PRESS_N=0.40; LOCK=lock; OPEN_V=ov
            def update(s,gap,finger=0.0,t=None,age=0.0):
                was=s.p
                r=pl.New.update(s,gap,finger,t,age)
                if was and s.p and len(s.hist)>=2:
                    pass
                if t<s.lock and gap>=0.025: s.p=False; r=False
                if (not was) is False and False: pass
                if was and (not s.p) and t-s.early_t>0: s.lock=t+s.LOCK
                return s.p
        print(f'fast release lock {lock} v>{ov}'.ljust(32),stats(R))
