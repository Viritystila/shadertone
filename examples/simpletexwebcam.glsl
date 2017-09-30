// The shadertoy uniform variables are available by default.
// iChannel0,2 should contain 2D textures
// use iChannel0's alpha to blend between 1 & 2

void main(void) {
  vec2 uv = (gl_FragCoord.xy / iResolution.xy);
  vec2 uv2 = (gl_FragCoord.xy / iResolution.xy);

  uv.x = uv.x + 1.5*sin(0.15*iGlobalTime);
  uv.y = uv.y + 1.5*cos(0.03*iGlobalTime);
  vec4 c1 = texture2D(iChannel0,uv);
  vec4 c2 = texture2D(iChannel0,uv2);

  vec4 c = mix(c1,c2,1.0-sin(c1.w));  // alpha blend between two textures
  gl_FragColor = c1;
}
