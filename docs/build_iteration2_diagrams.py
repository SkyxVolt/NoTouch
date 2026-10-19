from pathlib import Path
import math
import xml.etree.ElementTree as ET
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor
import fitz

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / 'iteration2'
OUT.mkdir(parents=True, exist_ok=True)
W, H = 1600, 900
INK, MUTED, BLUE, GREEN, AMBER = '#172B4D', '#53657B', '#2457D6', '#087F76', '#A66112'
pages = []

def page(title, subtitle):
    p = dict(title=title, subtitle=subtitle, boxes=[], edges=[], notes=[])
    pages.append(p)
    return p

def box(p, id, x, y, w, h, title, lines, color=BLUE, fill='#EEF3FF', dashed=False):
    p['boxes'].append(dict(id=id,x=x,y=y,w=w,h=h,title=title,lines=lines,color=color,fill=fill,dashed=dashed))

def edge(p, source, target, points, dashed=False):
    p['edges'].append(dict(source=source,target=target,points=points,dashed=dashed))

p = page('NoTouch | Runtime pipeline', 'ITERATION 2 / P4     Kim Gyulim + Anna     |     Implemented RGB path; physical-device validation pending')
box(p,'camera',55,180,260,140,'01  Front RGB camera',['CameraX / latest frame','Rotate to upright bitmap','Single analysis worker'])
box(p,'mp',360,180,260,140,'02  MediaPipe',['Bundled Face Landmarker','478 face / iris landmarks','Facial transformation matrix'])
box(p,'features',665,180,300,140,'03  Feature extraction',['Iris offsets + eye openness','Face position, scale, roll','Head yaw/pitch + distance*'])
box(p,'mapping',1010,180,530,140,'04  Local gaze mapping',['Compatible saved model, otherwise heuristic','Depth model: supervised personal regression','Output: normalized screen coordinates (x, y)'])
edge(p,'camera','mp',[(315,250),(360,250)])
edge(p,'mp','features',[(620,250),(665,250)])
edge(p,'features','mapping',[(965,250),(1010,250)])
box(p,'gate',1010,395,530,145,'05  Validate + smooth',['Reject delayed / invalid predictions','Reset on tracking loss, gaps or model change','Adaptive temporal smoothing; preserve off-screen x/y'])
edge(p,'mapping','gate',[(1275,320),(1275,395)])
box(p,'actions',520,395,420,145,'06  Interaction state machine',['Long closure: arm / confirm click','Off-screen vertical dwell: scroll','Two short blinks: start voice input'])
edge(p,'gate','actions',[(1010,468),(940,468)])
box(p,'system',55,395,395,145,'07  Android output',['Red-dot overlay + TrackerState.sample','Accessibility: click and scroll','VoiceInput: speech transcription'])
edge(p,'actions','system',[(520,468),(450,468)])
box(p,'cloud',55,625,700,125,'Optional cloud assistance',['NoTouch server / OpenAI API: suggest UI click targets','Candidate validated against current screen; does not replace red-dot gaze'],GREEN,'#EAF7F4',True)
edge(p,'cloud','actions',[(730,625),(730,540)],True)
box(p,'failure',815,625,725,125,'Recovery behavior',['Camera error / stale stream: hide pointer and clear pending actions','Missing face is never treated as a deliberate eye closure'],GREEN,'#EAF7F4')
p['notes']=[(55,155,'LOCAL ANDROID PATH  /  no network required for the core gaze pointer',BLUE),
 (55,795,'* Distance is an iris-size estimate using camera metadata and pose gating; it is not a depth-sensor measurement.',MUTED),
 (55,827,'Evidence: 41 unit tests passed; debug APK built. Device accuracy, latency and interaction success remain unmeasured.',MUTED)]

p = page('NoTouch | Personal learning & next steps','ITERATION 2 / P4     Training code implemented; user data and measured accuracy are not included')
box(p,'targets',55,180,310,155,'01  Touch-labelled sessions',['Look at target, then touch it','Collect stable pre-touch features','60 targets per completed session','Stored locally as numeric CSV'])
box(p,'split',415,180,310,155,'02  Session-level split',['At least 3 complete sessions','Earlier sessions: training','Latest session: held-out testing','Same device / display setup'])
box(p,'fit',775,180,310,155,'03  Fit two regressors',['Standardized ridge regression','With / without distance feature','Head-pose features in both','Eye x distance terms in full model'])
box(p,'eval',1135,180,405,155,'04  Evaluate & save',['Held-out mean / median / P95 error','Report prediction coverage','Save full distance-aware model','Improvement is not assumed'])
for a,b,x1,x2 in [('targets','split',365,415),('split','fit',725,775),('fit','eval',1085,1135)]:
    edge(p,a,b,[(x1,258),(x2,258)])
box(p,'load',1010,405,530,120,'Runtime model loading',['ModelStore checks device / display compatibility','Local model feeds runtime gaze mapping'])
edge(p,'eval','load',[(1338,335),(1338,405)])
box(p,'pending',55,405,870,120,'Next acceptance gate: a connected phone',['Measure horizontal response, fixation jitter, head-motion drift and distance effects','Verify click / scroll / voice actions and recovery on the actual device'],GREEN,'#EAF7F4')
box(p,'ir',55,645,400,110,'Future: USB IR camera',['Camera not acquired','Android capture / eye quality unverified'],AMBER,'#FFF6E8',True)
box(p,'fusion',510,645,485,110,'Future: synchronized RGB + IR',['IR pupil / iris features + RGB head pose','Clock alignment and mount calibration needed'],AMBER,'#FFF6E8',True)
box(p,'newmodel',1050,645,490,110,'Future: retrain and compare',['New feature schema and paired training data','Do not reuse RGB weights for IR pixel features'],AMBER,'#FFF6E8',True)
edge(p,'ir','fusion',[(455,700),(510,700)],True)
edge(p,'fusion','newmodel',[(995,700),(1050,700)],True)
p['notes']=[(55,155,'PERSONAL TRAINING  /  implementation exists; no pre-trained personal weights are bundled',BLUE),
 (55,600,'DEFERRED HARDWARE PATH  /  design only; not implemented or validated',AMBER),
 (55,810,'Touch location is a proxy label for gaze, not independently measured ground truth. Use held-out sessions for evaluation.',MUTED),
 (55,842,'Current artifacts: CaptureActivity, DepthTraining, DepthModel, ModelStore. No IR acquisition, iris-texture tracker or fusion code.',MUTED)]

pdf=OUT/'NoTouch-Iteration2-Pipeline.pdf'
c=canvas.Canvas(str(pdf),pagesize=(W,H))
c.setTitle('NoTouch - Iteration 2 Pipeline')
mx=ET.Element('mxfile',host='app.diagrams.net')

def txt(x,y,text,size=19,color=INK,bold=False):
    c.setFillColor(HexColor(color)); c.setFont('Helvetica-Bold' if bold else 'Helvetica',size)
    c.drawString(x,H-y,text)

for n,p in enumerate(pages,1):
    c.setFillColor(HexColor('#FFFFFF')); c.rect(0,0,W,H,stroke=0,fill=1)
    c.setFillColor(HexColor(BLUE)); c.rect(55,H-57,42,6,stroke=0,fill=1)
    txt(55,98,p['title'],36,bold=True)
    txt(55,123,p['subtitle'],17,MUTED)
    d=ET.SubElement(mx,'diagram',id=f'page{n}',name=p['title'])
    model=ET.SubElement(d,'mxGraphModel',dx='1600',dy='900',grid='1',gridSize='10',page='1',pageWidth=str(W),pageHeight=str(H))
    root=ET.SubElement(model,'root'); ET.SubElement(root,'mxCell',id='0'); ET.SubElement(root,'mxCell',id='1',parent='0')
    def cell(id,value,style,x,y,w,h):
        el=ET.SubElement(root,'mxCell',id=id,value=value,style=style,vertex='1',parent='1')
        ET.SubElement(el,'mxGeometry',x=str(x),y=str(y),width=str(w),height=str(h),attrib={'as':'geometry'})
    cell('title',p['title'],'text;html=0;align=left;fontSize=36;fontStyle=1;fontColor='+INK,55,57,1490,45)
    cell('subtitle',p['subtitle'],'text;html=0;align=left;fontSize=17;fontColor='+MUTED,55,103,1490,25)
    for b in p['boxes']:
        x,y,w,h=b['x'],b['y'],b['w'],b['h']
        c.setFillColor(HexColor(b['fill'])); c.setStrokeColor(HexColor(b['color'])); c.setLineWidth(1.5)
        c.setDash([7,5] if b['dashed'] else [])
        c.roundRect(x,H-y-h,w,h,12,stroke=1,fill=1); c.setDash([])
        txt(x+18,y+32,b['title'],21,b['color'],True)
        for j,line in enumerate(b['lines']):
            assert c.stringWidth(line,'Helvetica',18) < w-32, (b['id'],line)
            txt(x+18,y+62+j*23,line,18)
        value='<b>'+b['title']+'</b><br><br>'+'<br>'.join(b['lines'])
        cell(b['id'],value,f'rounded=1;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacing=18;fontSize=18;fillColor={b["fill"]};strokeColor={b["color"]};fontColor={INK};dashed={int(b["dashed"])};',x,y,w,h)
    for i,e in enumerate(p['edges']):
        pts=e['points']; c.setStrokeColor(HexColor(MUTED)); c.setFillColor(HexColor(MUTED));c.setLineWidth(2)
        c.setDash([6,5] if e['dashed'] else [])
        for (x,y),(xx,yy) in zip(pts,pts[1:]): c.line(x,H-y,xx,H-yy)
        c.setDash([])
        (x,y),(xx,yy)=pts[-2:]; a=math.atan2(yy-y,xx-x)
        path=c.beginPath();path.moveTo(xx,H-yy)
        for delta in [.48,-.48]:path.lineTo(xx-11*math.cos(a+delta),H-(yy-11*math.sin(a+delta)))
        path.close();c.drawPath(path,fill=1,stroke=0)
        el=ET.SubElement(root,'mxCell',id=f'edge{i}',source=e['source'],target=e['target'],edge='1',parent='1',style=f'edgeStyle=orthogonalEdgeStyle;rounded=0;endArrow=block;strokeColor={MUTED};strokeWidth=2;dashed={int(e["dashed"])};')
        ET.SubElement(el,'mxGeometry',relative='1',attrib={'as':'geometry'})
    for i,(x,y,t,color) in enumerate(p['notes']):
        txt(x,y,t,17,color)
        cell(f'note{i}',t,f'text;html=0;align=left;fontSize=17;fontColor={color};',x,y-20,1490,28)
    txt(1460,880,f'{n} / 2',14,MUTED)
    c.showPage()
c.save()
ET.indent(mx); ET.ElementTree(mx).write(OUT/'NoTouch-Iteration2-Pipeline.drawio',encoding='utf-8',xml_declaration=True)
doc=fitz.open(pdf)
for i,p in enumerate(doc):
    stem='NoTouch-Runtime-Pipeline' if i==0 else 'NoTouch-Training-and-IR-Roadmap'
    p.get_pixmap(matrix=fitz.Matrix(1.5,1.5),alpha=False).save(str(OUT/(stem+'.png')))
    (OUT/(stem+'.svg')).write_text(p.get_svg_image(),encoding='utf-8')
print(OUT)


