// The shadertoy uniform variables are available by default.
// iChannel0,2 should contain 2D textures
// use iChannel0's alpha to blend between 1 & 2

void main(void) {
  vec2 uv = (gl_FragCoord.xy / iResolution.xy);
  uv.y=1.0-uv.y;
  vec2 uv2 = (gl_FragCoord.xy / iResolution.xy);
  	vec2 p = gl_FragCoord.xy / iResolution.x;//normalized coords with some cheat
  uv2.y=1.0-uv2.y;

  float prop = iResolution.x / iResolution.y;//screen proroption
  vec2 m = vec2(0.5, 0.5 / prop);//center coords
  vec2 d = p - m;//vector from center to current fragment
  float r = sqrt(dot(d, d)); // distance of pixel from center

  float power = ( 1.0 * 3.141592 / (2.0 * sqrt(dot(m, m))) ) *
				(200*sin(1.15*iGlobalTime) / iResolution.x - 0.5);//amount of effect
  float bind;//radius of 1:1 effect
	if (power > 0.0) bind = sqrt(dot(m, m));//stick to corners
	else {if (prop < 1.0) bind = m.x; else bind = m.y;}//stick to borders
    vec2 uv3;
    if (power > 0.0)//fisheye
		uv3 = m + normalize(d) * tan(r * power) * bind / tan( bind * power);
	else if (power < 0.0)//antifisheye
		uv3 = m + normalize(d) * atan(r * -power * 10.0) * bind / atan(-power * bind * 10.0);
	else uv3 = p;//no effect for power = 1.0

  
  uv.x = uv.x + 5.5*sin(0.15*iGlobalTime);
  uv.y = uv.y + 2.5*cos(1.03*iGlobalTime);
  vec4 c1 = texture2D(iChannel0,uv);
  vec4 c2 = texture2D(iCam0,vec2(uv3.x, -uv3.y * prop));
  vec4 c3 = texture2D(iCam1,uv2);
  vec4 c4 = texture2D(iCam2,uv2);
  vec4 c5 = texture2D(iCam3,uv2);
  vec4 c6 = texture2D(iCam4,uv2);

  
  vec4 c = mix(c1,c2,0.3-sin(c1.w));  // alpha blend between two textures
  vec4 cf = mix(c4,c,1.5-sin(c1.w));  // alpha blend between two textures
  vec4 cf1 = mix(cf,c5,1.5-sin(c1.w));  // alpha blend between two textures
  vec4 cf2 = mix(c6,cf1,1.5-sin(c1.w));  // alpha blend between two textures
  //vec4 cf3 = mix(c5,c4,1.0-sin(c1.w));  // alpha blend between two textures

  gl_FragColor = c;
  
}
