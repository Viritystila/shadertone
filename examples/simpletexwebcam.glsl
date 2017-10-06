// The shadertoy uniform variables are available by default.
// iChannel0,2 should contain 2D textures
// use iChannel0's alpha to blend between 1 & 2

void main(void) {
  vec2 uv = (gl_FragCoord.xy / iResolution.xy);
  vec2 uv2 = (gl_FragCoord.xy / iResolution.xy);

  //uv.x = uv.x + 5.5*sin(0.15*iGlobalTime);
  //uv.y = uv.y + 2.5*cos(1.03*iGlobalTime);
  vec4 c1 = texture2D(iChannel0,uv);
  vec4 c2 = texture2D(iCam0,uv2);
  vec4 c3 = texture2D(iCam1,uv);

  vec4 c = mix(c3,c2,0.9-sin(c1.r));  // alpha blend between two textures
  gl_FragColor = c3;
  ;
}
