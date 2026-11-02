"""Rebuild the 20 curated five-piece catalog kits from pinned MobileSAM weights.

Requires onnxruntime==1.20.0, numpy, Pillow. Source prompts are reviewed
metadata, not training labels or hand-fitting coordinates. No hand photo is used.
"""
import sys, json, hashlib, math
from pathlib import Path
import numpy as np
import onnxruntime as ort
from PIL import Image,ImageDraw

def connected(raw,x,y):
    h,w=raw.shape
    assert raw[y,x], 'Prompt is not inside the predicted nail'
    clean=np.zeros_like(raw);clean[y,x]=True;stack=[(x,y)]
    while stack:
        xx,yy=stack.pop()
        for a,b in ((xx-1,yy),(xx+1,yy),(xx,yy-1),(xx,yy+1)):
            if 0<=a<w and 0<=b<h and raw[b,a] and not clean[b,a]:clean[b,a]=True;stack.append((a,b))
    return clean

def clean_plate(raw):
    # Press-on plates have no through-holes: white painted motifs must stay opaque.
    h,w=raw.shape;outside=np.zeros_like(raw);stack=[]
    for x in range(w):
        for y in [0,h-1]:
            if not raw[y,x] and not outside[y,x]:outside[y,x]=True;stack.append((x,y))
    for y in range(h):
        for x in [0,w-1]:
            if not raw[y,x] and not outside[y,x]:outside[y,x]=True;stack.append((x,y))
    while stack:
        x,y=stack.pop()
        for xx,yy in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
            if 0<=xx<w and 0<=yy<h and not raw[yy,xx] and not outside[yy,xx]:outside[yy,xx]=True;stack.append((xx,yy))
    filled=~outside;radius=max(2,round(min(w,h)*.035))
    offsets=[(dx,dy) for dx in range(-radius,radius+1) for dy in range(-radius,radius+1) if dx*dx+dy*dy<=radius*radius]
    padded=np.pad(filled,radius);eroded=np.ones_like(filled)
    for dx,dy in offsets:eroded &= padded[radius+dy:radius+dy+h,radius+dx:radius+dx+w]
    padded=np.pad(eroded,radius);opened=np.zeros_like(filled)
    for dx,dy in offsets:opened |= padded[radius+dy:radius+dy+h,radius+dx:radius+dx+w]
    return opened & filled

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'android/app/src/main/assets'
DEST=ASSETS/'nail-kits'
REPORT=ROOT/'android/app/build/reports/nail-kit-catalog'
REPORT.mkdir(parents=True,exist_ok=True)
options=ort.SessionOptions();options.intra_op_num_threads=2
encoder=ort.InferenceSession(str(ASSETS/'models/mobile_sam_image_encoder.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
decoder=ort.InferenceSession(str(ASSETS/'models/sam_mask_decoder_single.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
prompts=json.loads((DEST/'catalog-prompts.json').read_text(encoding='utf-8'))
summary={}
for key,entry in prompts.items():
    path=ROOT/'android/app/src/main/res/drawable'/entry['source']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sourceSha256']
    source=Image.open(path).convert('RGB');w,h=source.size;gain=1024/max(w,h)
    sw,sh=round(w*gain),round(h*gain)
    embedding=encoder.run(None,{'input_image':np.asarray(source.resize((sw,sh),Image.Resampling.BILINEAR),dtype=np.float32)})[0]
    pieces=[];overlay=source.copy();draw=ImageDraw.Draw(overlay)
    folder=DEST/key;folder.mkdir(exist_ok=True)
    ordered=entry['prompts'] if entry['thumbAtStart'] else entry['prompts'][::-1]
    for finger,prompt in zip(['THUMB','INDEX','MIDDLE','RING','LITTLE'],ordered):
        x,y=prompt['point'];l,t,r,b=prompt['box']
        points=[[x,y],[l,t],[r,b]]+[p['point'] for p in entry['prompts'] if p is not prompt]
        labels=[1,2,3]+[0]*4
        coords=np.asarray(points,dtype=np.float32);coords[:,0]*=sw/w;coords[:,1]*=sh/h
        mask,iou,_=decoder.run(None,{'image_embeddings':embedding,'point_coords':coords[None],
            'point_labels':np.asarray([labels],np.float32),'mask_input':np.zeros((1,1,256,256),np.float32),
            'has_mask_input':np.zeros(1,np.float32),'orig_im_size':np.asarray([h,w],np.float32)})
        raw=mask[0,0]>0
        clean=connected(raw,x,y)
        ry,rx=np.where(clean);raw_box=[int(rx.min()),int(ry.min()),int(rx.max())+1,int(ry.max())+1]
        rl,rt,rr,rb=raw_box
        clean[rt:rb,rl:rr]=clean_plate(clean[rt:rb,rl:rr])
        clean=connected(clean,x,y)
        ys,xs=np.where(clean);assert len(xs)>100,f'{key}/{finger} empty'
        assert clean.sum()<w*h*.10,f'{key}/{finger} selected card/background'
        # Boxes locate the nail; the SAM silhouette, not a rectangle, supplies alpha.
        box=[int(xs.min()),int(ys.min()),int(xs.max())+1,int(ys.max())+1]
        assert box[0]<=x<box[2] and box[1]<=y<box[3]
        l,t,r,b=box
        rgba=np.asarray(source).copy();rgba=np.dstack((rgba,clean.astype(np.uint8)*255))
        crop=Image.fromarray(rgba[t:b,l:r]);crop.save(folder/(finger+'.png'))
        pad=round(max(r-l,b-t)*.18)
        context=[max(0,rl-pad),max(0,rt-pad),min(w,rr+pad),min(h,rb+pad)]
        source.crop(context).save(folder/(finger+'-context.jpg'),quality=95)
        pts=np.column_stack((xs,ys));eigenvectors=np.linalg.eigh(np.cov(pts.T))[1];axis=eigenvectors[:,-1]
        angle=math.atan2(float(axis[1]),float(axis[0]))
        pieces.append({'finger':finger,'file':finger+'.png','contextFile':finger+'-context.jpg','sourceBox':box,
            'contextBox':context,'center':[float(xs.mean()+.5),float(ys.mean()+.5)],'axisRadians':angle,
            'maskArea':int(clean.sum()),'confidence':float(iou[0,0]),'sourcePoint':prompt['point']})
        edge=clean & ~(np.roll(clean,1,0)&np.roll(clean,-1,0)&np.roll(clean,1,1)&np.roll(clean,-1,1))
        for xx,yy in zip(*np.where(edge)[::-1]):draw.point((int(xx),int(yy)),fill='lime')
        draw.text((x,y),finger,fill='red')
    # Instance masks must not reuse the same pixels from adjacent nails/accessories.
    planes=[]
    for piece in pieces:
        plane=np.zeros((h,w),bool);l,t,r,b=piece['sourceBox']
        plane[t:b,l:r]=np.asarray(Image.open(folder/piece['file']))[:,:,3]>0;planes.append(plane)
    yy,xx=np.mgrid[:h,:w]
    distances=np.stack([np.where(plane,(xx-p['sourcePoint'][0])**2+(yy-p['sourcePoint'][1])**2,np.inf) for plane,p in zip(planes,pieces)])
    owner=distances.argmin(axis=0)
    for i,piece in enumerate(pieces):
        x,y=piece['sourcePoint'];clean=connected(planes[i] & (owner==i),x,y)
        ys,xs=np.where(clean);l,t,r,b=int(xs.min()),int(ys.min()),int(xs.max())+1,int(ys.max())+1
        rgba=np.dstack((np.asarray(source),clean.astype(np.uint8)*255))
        Image.fromarray(rgba[t:b,l:r]).save(folder/piece['file'])
        piece['sourceBox']=[l,t,r,b];piece['maskArea']=int(clean.sum());piece['center']=[float(xs.mean()+.5),float(ys.mean()+.5)]
        axis=np.linalg.eigh(np.cov(np.column_stack((xs,ys)).T))[1][:,-1]
        piece['axisRadians']=math.atan2(float(axis[1]),float(axis[0]))
    upright=[]
    for piece in pieces:
        angle=piece['axisRadians']
        if math.sin(angle)>0:angle-=math.pi
        upright.append(Image.open(folder/piece['file']).convert('RGBA').rotate(90+math.degrees(angle),resample=Image.Resampling.BICUBIC,expand=True))
    scale=min(1,320/max(im.height for im in upright));sizes=[(max(1,round(im.width*scale)),max(1,round(im.height*scale))) for im in upright]
    reference=Image.new('RGB',(sum(s[0] for s in sizes)+144,max(s[1] for s in sizes)+48),'white');xx=24
    for im,size in zip(upright,sizes):
        im=im.resize(size,Image.Resampling.LANCZOS);reference.paste(im,(xx,24),im);xx+=size[0]+24
    reference.save(folder/'reference.png')
    manifest={'version':1,'source':entry['source'],'sourceSha256':entry['sourceSha256'],'width':w,'height':h,
        'selection':'explicit-left' if entry['leftMarked'] else 'unmarked-first-row','pieces':pieces}
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    overlay.save(REPORT/(key+'.jpg'))
    summary[key]=[(p['finger'],p['maskArea'],round(p['confidence'],3)) for p in pieces]
    print(key,summary[key],flush=True)
(REPORT/'summary.json').write_text(json.dumps(summary,indent=2))
