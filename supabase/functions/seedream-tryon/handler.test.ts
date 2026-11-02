import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHandler} from './handler.ts';
const model = 'doubao-seedream-5-0-pro-260628';
const data = (x: string) => `data:image/png;base64,${Buffer.from(x).toString('base64')}`;
const body = {handImage:data('hand'),nailImage:data('five nails'),guideImage:data('five locations'),prompt:'one-to-one left-hand transfer'};
const req = (value: unknown = body) => new Request('https://local/tryon',{method:'POST',body:JSON.stringify(value)});
test('forwards original, ordered five-piece reference and guide once, with configured model', async()=>{
  let calls=0;
  const response=await createHandler({apiKey:'private',model},async(url,options)=>{
    calls++;assert.match(String(url),/ark.cn-beijing.volces.com/);
    assert.equal(options.headers.Authorization,'Bearer private');
    const input=JSON.parse(options.body);assert.deepEqual(input.image,[body.handImage,body.nailImage,body.guideImage]);
    assert.equal(input.model,model);assert.equal(input.size,'2K');assert.equal(input.watermark,false);
    assert.equal(input.sequential_image_generation,undefined);
    return Response.json({data:[{b64_json:'YWJj'}],model});
  })(req());
  assert.equal(calls,1);assert.equal(response.status,200);
  assert.deepEqual(await response.json(),{model,imageDataUrl:'data:image/jpeg;base64,YWJj'});
});
test('legacy two-image clients remain compatible',async()=>{
  const response=await createHandler({apiKey:'key',model},async(_,options)=>{
    assert.equal(JSON.parse(options.body).image.length,2);return Response.json({data:[{b64_json:'YWJj'}]});
  })(req({...body,guideImage:undefined}));assert.equal(response.status,200);
});
test('invalid input never incurs a paid call',async()=>{
  let calls=0;const handler=createHandler({apiKey:'key',model},async()=>{calls++;throw Error();});
  for(const value of [null,[],{}, {...body,guideImage:'http://internal'}, {...body,handImage:'data:image/png;base64,%%%'} ,{...body,prompt:''}])
    assert.equal((await handler(req(value))).status,400);
  assert.equal(calls,0);
});
test('authentication and missing configuration stop before provider',async()=>{
  assert.equal((await createHandler({model})(req())).status,503);
  assert.equal((await createHandler({apiKey:'key',model,demoToken:'protected'})(req())).status,401);
});
test('provider errors and network failures return controlled errors without secrets',async()=>{
  for(const status of [400,401,429,500]){
    const response=await createHandler({apiKey:'key',model},async()=>Response.json({secret:'DO NOT ECHO'},{status}))(req());
    assert.equal(response.status,status===429?429:502);assert.ok(!(await response.text()).includes('DO NOT ECHO'));
  }
  const response=await createHandler({apiKey:'key',model},async()=>{throw new DOMException('timeout','TimeoutError')})(req());
  assert.equal(response.status,504);assert.equal((await response.json()).error,'seedream_timeout');
});
test('empty or malformed success cannot become a fake generated image',async()=>{
  for(const value of [{}, {data:[]}, {data:[{b64_json:'bad!'}]}])
    assert.equal((await createHandler({apiKey:'key',model},async()=>Response.json(value))(req())).status,502);
});
test('options, methods and malformed JSON handled',async()=>{
  const handler=createHandler({apiKey:'key',model});
  assert.equal((await handler(new Request('https://local',{method:'OPTIONS'}))).status,200);
  assert.equal((await handler(new Request('https://local'))).status,405);
  assert.equal((await handler(new Request('https://local',{method:'POST',body:'bad'}))).status,400);
});

test('provider diagnostics redact key, prompt and all image data',async()=>{
  const response=await createHandler({apiKey:'private-key',model},async()=>Response.json({error:{code:'InvalidParameter',message:`private-key ${body.prompt} ${body.handImage} ${body.nailImage} ${body.guideImage}`}},{status:400}))(req());
  const text=await response.text();for(const value of ['private-key',body.prompt,body.handImage,body.nailImage,body.guideImage])assert.ok(!text.includes(value));
  assert.match(text,/redacted/);
});
