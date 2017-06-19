import processing.core.*; 
import processing.data.*; 
import processing.event.*; 
import processing.opengl.*; 

import controlP5.*; 
import processing.serial.*; 
import javax.swing.JOptionPane; 

import java.util.HashMap; 
import java.util.ArrayList; 
import java.io.File; 
import java.io.BufferedReader; 
import java.io.PrintWriter; 
import java.io.InputStream; 
import java.io.OutputStream; 
import java.io.IOException; 

public class portrait_bot_v2 extends PApplet {

////////////////////////////////////////////////////////////////////////////////
//                                                                            //
// brush-bot Airbrushing Robot | The Living | 2016                            //
// Portrait Mode                                                              //
// v4.0 2017.06.12                                                            //
//                                                                            //
////////////////////////////////////////////////////////////////////////////////
// ACKNOWLEGDEMENTS
// GRBL streaming methods & Serial connection based on GCTRL
// https://github.com/damellis/gctrl

// EXTERNAL DEPENDENCIES
//------------------------------------------------------------------------------
 //ControlP5 - UI Interface
 //Serial - Com protocol with Arduino
 //Interface for COM port selection

// GLOBAL VARIABLES
//------------------------------------------------------------------------------
// DEBUG
Boolean VERBOSE = false;
int reportFreq = 20; //
// IO
String fp = "";
// UX
ControlP5 cP5;
PFont font24, font18, font16i, font14, font12;
int black, white, grey, charcoal, green, red, blue;
PVector origin;
float scalar;
PShape preview;
// GCODE
StringList gcode;
int line;
// MACHINE
String status;
int poll = 0;
float posx, posy, lastx, lasty, spray_speed;
float canvas_width, canvas_height, canvas_margin;
int sprayoff = 10;
int sprayon = 200;
// STATUS
Boolean streaming, spraying, paused, loaded;
// SERIAL
Serial port;
String portname;
String val, sent;
Boolean connected;
int r = 0;
int timeout = 0;

// SETUP
//------------------------------------------------------------------------------
public void setup() {
  settings();
  initVariables();
  initFonts();
  initColors();

  initPreview( );

  setupControls();
  selectSerial();
}

// DRAW
//------------------------------------------------------------------------------
public void draw(){
  displayUI();

  renderPreview( );

  displayStats();
  checkStatus();


  // realtime reporting
  if(connected && r>reportFreq){
    statusReport(paused);
  }


  if (connected) serialRun();
  renderNozzle();

  // if( poll > 20) poll = 1;

}

// SETTINGS
//------------------------------------------------------------------------------
public void settings(){
  size(1300, 750);
  smooth();
}

// INIT VARIABLES
public void initVariables(){
  // UX
  origin = new PVector(950,375);
  scalar = 0.5f;
  // GCODE
  gcode = new StringList();
  line = 0;
  // MACHINE
  posx = 0.0f;
  posy = 0.0f;
  status = "";
  spray_speed = 5000.0f;
  canvas_width = 1220.0f;
  canvas_height = 1220.0f;
  canvas_margin = 10.0f;
  //STATUS
  streaming = false;
  spraying = false;
  paused = false;
  loaded = false;
  // SERIAL
  port = null;
  portname = null;
  val = "...";
  sent = "...";
  connected = false;
}

public float parseNumber(String s, String c, float f){
  c = c.toUpperCase();
  s = s.toUpperCase();
  int index = s.indexOf(c);
  if( index < 0 ) return f;
  int endIndex = s.indexOf(" ",index);
  if( endIndex  < 0 ) endIndex = s.length();
  return PApplet.parseFloat( s.substring(index+1, endIndex) );
}

////////////////////////////////////////////////////////////////////////////////
// RENDERING
////////////////////////////////////////////////////////////////////////////////

// INITIALIZE GCODE PREVIEW
public void initPreview(){
  preview = new PShape();
}

// RENDER PREVIEW TO CANVAS
public void renderPreview(){
  if( preview == null ) return;
  preview.enableStyle();
  shape(preview, origin.x - (canvas_width*0.5f*scalar), origin.y+(canvas_height*0.5f*scalar));
}

// GENERATE PREVIEW
public void generatePreview(StringList g){
  preview = new PShape();
  PVector last = new PVector(0,0);
  int type;
  int c;
  float o;
  float w;

  for(int i = 0; i<g.size(); i++){
    String cmd = g.get(i);
    if( cmd.startsWith("G0 ") || cmd.startsWith("G1 ")){
      type = PApplet.parseInt( cmd.charAt(1) );
      c = (type=='0') ? blue : red;
      w = (type=='0') ? 1 : 3;
      o = (type=='0') ? 255 : 55;

      renderLine(last, cmd, c, o, w);
    }
  }
}

public void renderLine(PVector l, String cmd, int c, float o, float w ){
  PShape ln;
  float x = parseNumber(cmd,"X",l.x);
  float y = parseNumber(cmd,"Y",l.y);
  stroke(c,o);
  strokeWeight(w);
  ln = createShape( LINE, l.x*scalar, -l.y*scalar, x*scalar, -y*scalar );
  // line(origin.x+l.x*scalar, origin.y-l.y*scalar,origin.x+x*scalar, origin.y-y*scalar);
  preview.addChild( ln );
  l.x = x;
  l.y = y;
}

////////////////////////////////////////////////////////////////////////////////
// GCODE
////////////////////////////////////////////////////////////////////////////////
// G0/G1 - LINE COMMAND
public String gLine(float x, float y, boolean f){
  String cmd = (f) ? "G1" : "G0";
  cmd += " X"+str(x) + " Y"+str(y);
  return cmd;
}

// G2/G3 - ARC COMMANDS
public String gArc(float cx, float cy, float x, float y, boolean dir){
  //clockwise = 2 ... counterclockwise = 3
  if( dir ) return "G2 I"+str(cx) + " J"+str(cy) + " X"+str(x) + " Y"+str(y) + " F"+str(PApplet.parseInt(spray_speed));
  else return "G3 I" + str(cx) + " J" + str(cy) + " X" + str(x) + " Y" + str(y) + " F"+str(PApplet.parseInt(spray_speed));
}

// G4 - PAUSE COMMAND
public String gDwell( float time ){
  return "G4 P" + str(time);
}

// M3 - SPRAY COMMAND
public String gSpray( boolean s ){
  return "M3 S" + ((s) ? str(sprayon) : str(sprayoff));
}

// Report
public String report(){
  return "?";
}

// JOGGING
public String jog(float x, float y){
  String cmd = "G91";
  cmd += gLine(x,y,false);
  return cmd + "\nG90";
}

// SET ORIGIN
public String origin(){
  posx = 0.0f;
  posy = 0.0f;
  return "G10 P1 L20 X0 Y0";
}
// GO HOME
public String home(){
  return gLine(0,0,false);
}

public String gPause(){
  return "!";
}

public Byte gDoor(){
  return PApplet.parseByte(0x84);
}

public Byte gReset(){
  return PApplet.parseByte(0x18);
}

public String gResume(){
  return "~";
}

// PARK (GO TO MACHINE ZERO)
public String park(){
  return "G53 X0 Y0";
}

////////////////////////////////////////////////////////////////////////////////
// FILE I/O
////////////////////////////////////////////////////////////////////////////////

// LOAD FILES
public void load(){
  selectFolder("Select a folder to process:", "folderSelected");
}
public void folderSelected( File f ){
  if( f == null ){
    print("Window closed or user cancelled\n");
    return;
  }
  fp = f.getAbsolutePath();
  print("User selected " + fp + "\n");
  String[] files = listFiles(fp);
  if( files == null || !checkDir(files,"json")){
    loaded = false;
    fp = "";
    print( ((files==null)?"ERROR--EMPTY OR INVALID DIRECTORY\n":"ERROR--NO JSON DRAWING FILES IN DIRECTORY\n"));
    return;
  }
  loaded = true;
  gcode = processFiles( files );
  print("DRAWINGS LOADED\n");
  print("GCODE LINES GENERATED: " + gcode.size() + "\n");
  generatePreview(gcode);
  print("GCODE PREVIEW GENERATED\n");
}

// LIST FILES IN DIRECTORY
public String[] listFiles( String dir ){
  File file = new File(dir);
  if( file.isDirectory() ){
    return file.list();
  }
  return null;
}

public Boolean fileCheck( String f, String ext ){
  return f.contains(ext);
}

// CHECK FILE EXTENSION
public Boolean checkDir( String[] files, String ext ){
  for( int i = 0; i<files.length; i++){
    if ( fileCheck(files[i], ext) ) return true;
  }
  return false;
}

// PROCESS FILES
public StringList processFiles( String[] f ){
  StringList g = new StringList(); //clear gcode buffer
  PVector p;

  g.append( gSpray(false) );
  g.append( home() );

  for( int i = 0; i < f.length; i++){
    if( !fileCheck(f[i],"json") ) continue;

    JSONArray coords = loadJSONArray( fp + "\\" + f[i] );

    p = extractPos( coords.getFloat(0), -coords.getFloat(1) );
    g.append( gLine( p.x, p.y, false ) );
    g.append( gSpray(true) );

    for( int k = 2; k < coords.size(); k+=2 ){
      p = extractPos( coords.getFloat(k),-coords.getFloat(k+1) );
      g.append( gLine(p.x, p.y, true) );
    }
    g.append( gSpray(false) );
  }
  g.append( gSpray(false) );
  g.append( home() );
  g.append( home() );

  print("GCODE LINES GENERATED: " + g.size() + "\n");

  saveStrings( "data/gcode.txt", g.array() );

  return g;
}

public PVector extractPos(float x, float y){
  float x_s = (canvas_width*0.5f)-canvas_margin;
  float y_s = (canvas_height*0.5f)-canvas_margin;
  float x_off = canvas_width*0.5f;
  float y_off = canvas_height*0.5f;

  return new PVector( x_off + x * x_s, y_off + y * y_s );
}

////////////////////////////////////////////////////////////////////////////////
// UX
////////////////////////////////////////////////////////////////////////////////

// INIT COLORS
public void initColors(){
  black = color(0);
  white = color(255);
  grey = color(220);
  charcoal = color(100);
  red = color(237, 28, 36);
  green = color(57, 181, 74);
  blue = color(80, 150, 225);
}

// INIT FONTS
public void initFonts(){
  font24 = loadFont("Roboto-Regular-24.vlw");
  font18 = loadFont("Roboto-Regular-18.vlw");
  font16i = loadFont("Roboto-Italic-16.vlw");
  font14 = loadFont("Roboto-Regular-14.vlw");
  font12 = loadFont("Roboto-Regular-12.vlw");
}

// DISPLAY UI
public void displayUI() {
  // UI CANVAS DIMENSIONS
  float scaleWidth = canvas_width*scalar;
  float scaleHeight = canvas_height*scalar;
  float scaleMargin = canvas_margin*scalar;

  // SETUP PREVIEW AREA
  // Canvas BG
  noStroke();
  fill(black);
  rect(600, 0, 700, 750);

  pushMatrix();
  rectMode(CENTER);

  // Canvas
  translate(origin.x,origin.y);
  fill(white);
  rect(0,0,scaleWidth,scaleHeight);
  fill(grey);
  rect(0,0,scaleWidth-scaleMargin*2,scaleHeight-scaleMargin*2);

  // Canvas Grid
  noFill();
  stroke(white);
  strokeWeight(1);
  for (float x = 0 ; x < scaleWidth*0.5f; x+=scalar*20) {
    line(x, -scaleHeight*0.5f, x, scaleHeight*0.5f);
    line(-x, -scaleHeight*0.5f, -x, scaleHeight*0.5f);
  }
  for (float y = 0; y < scaleHeight*0.5f; y+=scalar*20) {
    line(-scaleWidth*0.5f, y, scaleWidth*0.5f, y);
    line(-scaleWidth*0.5f, -y, scaleWidth*0.5f, -y);
  }

  // Canvas Frame
  strokeWeight(1);
  rect(0, 0, scaleWidth, scaleHeight);

  rectMode(CORNER);
  popMatrix();

  // MANUAL CONTROLS AREA
  // Controls BG
  noStroke();
  fill(grey);
  rect(0,0,600,900);
  // Controls Label
  fill(black);
  textFont(font24,24);
  textAlign(LEFT);
  text("MANUAL CONTROLS", 15, 30);
  // Console area
  fill(black);
  rect(0,375,590,525);
  noFill();
  stroke(charcoal);
  strokeWeight(1);
  rect(15,40,320,320);
}

// RENDER NOZZLE POSITION
public void renderNozzle(){
  pushMatrix();
  //Display Dimensions
  float scaleWidth = canvas_width*scalar;
  float scaleHeight = canvas_height*scalar;

  translate(origin.x-scaleWidth*0.5f,origin.y+scaleHeight*0.5f);

  // Nozzle Icon
  stroke( (spraying)?red:blue );
  // if(spraying) stroke(blue);
  fill(white,50);
  strokeWeight(3);
  ellipse(posx*scalar,-(posy*scalar),10,10);
  noFill();
  strokeWeight(0.5f);
  ellipse(posx*scalar, -(posy*scalar),20,20);

  // Nozzle Position Text
  String pos = "( "+nf(posx,0,2)+", "+nf(posy,0,2)+" )";
  fill( (spraying) ? red : blue );
  textFont(font14,14);
  textAlign(CENTER);
  text(pos,(posx*scalar),-(posy*scalar) + 24.0f);
  popMatrix();
}

// DISPLAY STATS
public void displayStats(){
  // TX Command
  if(sent != null){
    noStroke();
    fill(green);
    textAlign(LEFT);
    textFont(font24, 24);
    text(sent, 15, 490);
  }
  // RX Command
  if(val != null){
    noStroke();
    fill(red);
    textAlign(LEFT);
    textFont(font18, 18);
    text(val, 15, 520);
  }

  // Serial Status
  String status;
  textFont(font18,18);
  fill( ((connected) ? green : red) );
  status = (connected) ? "CONNECTED ON " + portname : "NOT CONNECTED";
  text(status, 15, 740);

}

// SET UP UX CONTROLS
public void setupControls() {
  cP5 = new ControlP5(this);

  // Global Settings
  cP5.setFont( font12 );
  cP5.setColorForeground( black );
  cP5.setColorBackground( white );
  cP5.setColorValueLabel( white );
  cP5.setColorCaptionLabel( white );
  cP5.setColorActive( blue );

  // Report Button
  cP5.addBang("report")
  .setPosition(origin.x-50,10)
  .setSize(100,25)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(white)
  .setColorActive(blue)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(black)
  .setFont(font12)
  .setText("REPORT")
  ;

  // Serial Connect Button
  cP5.addBang("connect")
  .setPosition(475,710)
  .setSize(100,25)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(white)
  .setColorActive(blue)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(black)
  .setFont(font12)
  .setText("CONNECT")
  ;

  // Park Machine Button
  cP5.addBang("park")
  .setPosition(25,318)
  .setSize(50,32)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(red)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(black)
  .setFont(font12)
  .setText("PARK")
  ;

  // Y+100 button
  cP5.addBang("y+100")
  .setPosition(150,50)
  .setSize(50,32)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("+100")
  ;
  // Y+10 button
  cP5.addBang("y+10")
  .setPosition(150,87)
  .setSize(50,32)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("+10")
  ;
  // Y+1 button
  cP5.addBang("y+1")
  .setPosition(150,124)
  .setSize(50,32)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("+1")
  ;
  // Y-100 button
  cP5.addBang("y-100")
  .setPosition(150,318)
  .setSize(50,32)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("-100")
  ;
  // Y-10 button
  cP5.addBang("y-10")
  .setPosition(150,281)
  .setSize(50,32)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("-10")
  ;
  // Y-1 button
  cP5.addBang("y-1")
  .setPosition(150,244)
  .setSize(50,32)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("-1")
  ;
  // X-100 button
  cP5.addBang("x-100")
  .setPosition(25,175)
  .setSize(32,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("-100")
  ;
  // X-10 button
  cP5.addBang("x-10")
  .setPosition(62,175)
  .setSize(32,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("-10")
  ;
  // X-1 button
  cP5.addBang("x-1")
  .setPosition(99,175)
  .setSize(32,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("-1")
  ;
  // X+100 button
  cP5.addBang("x+100")
  .setPosition(293,175)
  .setSize(32,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("+100")
  ;
  // X+10 button
  cP5.addBang("x+10")
  .setPosition(256,175)
  .setSize(32,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("+10")
  ;
  // X+1 button
  cP5.addBang("x+1")
  .setPosition(219,175)
  .setSize(32,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("+1")
  ;

  //Go Home Button
  cP5.addBang("home")
  .setPosition(140,165)
  .setSize(70,70)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font12)
  .setText("GO HOME")
  ;

  // Sprayer Off Button
  cP5.addBang("sprayOff")
  .setPosition(345,175)
  .setSize(120,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font14)
  .setText("SPRAY OFF")
  ;
  // Sprayer On Button
  cP5.addBang("sprayOn")
  .setPosition(470,175)
  .setSize(120,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font14)
  .setText("SPRAY ON")
  ;

  // Set Origin Button
  cP5.addBang("origin")
  .setPosition(345,300)
  .setSize(120,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(black)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font14)
  .setText("SET ORIGIN (0,0)")
  ;

  // Load Files Button
  cP5.addBang("load-folder")
  .setPosition(345,245)
  .setSize(120,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(blue)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(black)
  .setFont(font14)
  .setText("LOAD")
  ;

  // Start Button
  cP5.addBang("start")
  .setPosition(470,245)
  .setSize(120,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(green)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font14)
  .setText("RUN FILE")
  ;
  // Pause Button
  cP5.addBang("pause")
  .setPosition(470,300)
  .setSize(120,50)
  .setTriggerEvent(Bang.RELEASE)
  .setColorForeground(red)
  //caption settings
  .getCaptionLabel()
  .align(ControlP5.CENTER, ControlP5.CENTER)
  .setColor(white)
  .setFont(font14)
  .setText("PAUSE")
  ;

  // Canvas Width Entry
  cP5.addTextfield("width")
  .setPosition( 345, 50 )
  .setSize( 120, 30 )
  .setFont( font18 )
  .setFocus( false )
  .setColor( black )
  .setAutoClear( false )
  .setInputFilter( ControlP5.INTEGER )
  .setValue( nf(canvas_width) )
  //caption settings
  .getCaptionLabel()
  .setColor( black )
  .setFont( font14 )
  .alignX( ControlP5.LEFT )
  .setText( "WIDTH (mm)" )
  ;
  // Canvas Height Entry
  cP5.addTextfield("height")
  .setPosition( 470, 50 )
  .setSize( 120, 30 )
  .setFont( font18 )
  .setFocus( false )
  .setColor( black )
  .setAutoClear( false )
  .setInputFilter( ControlP5.INTEGER )
  .setValue( nf(canvas_height) )
  //caption settings
  .getCaptionLabel()
  .setColor( black )
  .setFont( font14 )
  .alignX( ControlP5.LEFT )
  .setText( "HEIGHT (mm)" )
  ;
  // Canvas Margin Entry
  cP5.addTextfield("margin")
  .setPosition( 345, 110 )
  .setSize( 120, 30 )
  .setFont( font18 )
  .setFocus( false )
  .setColor( black )
  .setAutoClear( false )
  .setInputFilter( ControlP5.INTEGER )
  .setValue( nf(canvas_margin) )
  //caption settings
  .getCaptionLabel()
  .setColor( black )
  .setFont( font14 )
  .alignX( ControlP5.LEFT )
  .setText( "MARGIN (mm)" )
  ;
  // Paint Speed Entry
  cP5.addTextfield("speed")
  .setPosition( 470, 110 )
  .setSize( 120, 30 )
  .setFont( font18 )
  .setFocus( false )
  .setColor( black )
  .setAutoClear( false )
  .setInputFilter( ControlP5.INTEGER )
  .setValue( nf(spray_speed) )
  //caption settings
  .getCaptionLabel()
  .setColor( black )
  .setFont( font14 )
  .alignX( ControlP5.LEFT )
  .setText( "SPEED (mm/min)" )
  ;

  // Manual Command Entry
  cP5.addTextfield("cmdEntry")
  .setPosition( 15, 390 )
  .setSize( 560, 50 )
  .setFont( font24 )
  .setFocus( true )
  .setColor( black )
  .setAutoClear( true )
  //caption settings
  .getCaptionLabel()
  .setColor(white)
  .setFont(font14)
  .alignX(ControlP5.LEFT)
  .setText("MANUAL GCODE ENTRY")
  ;
}

// UX CONTROL EVENTS
public void controlEvent( ControlEvent theEvent ) {
  if ( theEvent.isController() ) {
    String eventName = theEvent.getName();
    switch( eventName ) {
      case "report":
      statusReport(paused);
      break;
      case "connect":
      if(connected){
        port.stop();
        portname = null;
      }
      selectSerial();
      break;
      case "park":
      send( park() );
      break;
      case "y+100":
      send( jog( 0, 100 ) );
      break;
      case "y+10":
      send( jog( 0, 10 ) );
      break;
      case "y+1":
      send( jog(0, 1) );
      break;
      case "y-1":
      send( jog(0,-1) );
      break;
      case "y-10":
      send( jog(0, -10) );
      break;
      case "y-100":
      send( jog(0, -100) );
      break;
      case "x+100":
      send( jog(100, 0) );
      break;
      case "x+10":
      send( jog(10, 0) );
      break;
      case "x+1":
      send( jog(1, 0) );
      break;
      case "x-1":
      send( jog(-1, 0) );
      break;
      case "x-10":
      send( jog(-10, 0) );
      break;
      case "x-100":
      send( jog(-100, 0) );
      break;
      case "home":
      send( home() );
      break;
      case "sprayOff":
      // spraying = false;
      send( gSpray(false) );
      break;
      case "sprayOn":
      // spraying = true;
      send( gSpray(true) );
      break;
      case "origin":
      send( origin() );
      break;
      case "width":
      case "height":
      case "margin":
      updateDim();
      break;
      case "speed":
      updateSpeed();
      break;
      case "cmdEntry":
      send( manualEntry() );
      break;
      case "load-folder":
      load();
      break;
      case "start":
      if(paused){
        streaming = false;
        line = 0;
        sendByte( gReset() );
        poll++;
        send( home() );
        paused = false;
        break;
      }
      if(!streaming){
        updateSpeed();
        print("STARTING STREAM\n");
        streaming = true;
        stream();
      }
      break;
      case "pause":
      paused = !paused;
      if(paused){
        sendByte( gDoor() );
        poll++;
      } else {
        send( gResume() );
        poll++;
        streaming = true;
        stream();
      }
      break;
      default:
      break;
    }
  }
}

// CHECK STATUS
public void checkStatus(){
  Bang start = cP5.get(Bang.class, "start");
  Bang pause = cP5.get(Bang.class, "pause");
  Bang load = cP5.get(Bang.class, "load-folder");
  Bang origin = cP5.get(Bang.class, "origin");
  Bang connect = cP5.get(Bang.class, "connect");

  if( !connected ){
    lockButton( start, true, charcoal, grey );
    relabelButton( start, "START" );
    lockButton( pause, true, charcoal, grey );
    relabelButton( pause, "PAUSE" );
    relabelButton( connect, "CONNECT" );
    return;
  }

  if( connected ){
    relabelButton( connect, "RECONNECT" );
  }

  if( !loaded && !paused ){
    lockButton( start, true, charcoal, grey );
    relabelButton( start, "START" );
    lockButton( pause, false, red, white );
    relabelButton( pause, "PAUSE" );
    relabelButton( load, "LOAD");
    return;
  }

  if( !loaded && paused ){
    lockButton( start, true, charcoal, grey );
    relabelButton( start, "START" );
    lockButton( pause, false, green, white );
    relabelButton( pause, "RESUME" );
    relabelButton( load, "LOAD");
    return;
  }

  if( loaded ){
    relabelButton( load, "RELOAD" );
  }

  if( streaming && !paused ){
    lockButton( start, false, blue, white );
    relabelButton( start, "RUNNING" );
    lockButton( pause, false, red, white );
    relabelButton( pause, "PAUSE" );
    lockButton( load, true, charcoal, grey );
    lockButton( origin, true, charcoal, grey );
    lockButton( connect, true, charcoal, grey );
    return;
  }

  if( streaming && paused ){
    lockButton( start, false, red, white );
    relabelButton( start, "RESET" );
    lockButton( pause, false, green, white );
    relabelButton( pause, "RESUME" );
    lockButton( load, true, charcoal, grey );
    lockButton( origin, true, charcoal, grey );
    lockButton( connect, true, charcoal, grey );
    return;
  }

  lockButton( start, false, green, white );
  relabelButton( start, "START" );
  lockButton( pause, false, red, white );
  relabelButton( pause, "PAUSE" );
  lockButton( load, false, blue, black );
  lockButton( origin, false, black, white );
  lockButton( connect, false, white, black );

}

// RELABEL BUTTON
public void relabelButton(Bang button, String newlabel){
  button.getCaptionLabel().setText(newlabel);
}

// LOCK BUTTON
public void lockButton(Bang button, boolean lock, int c, int t){
  button.setLock(lock)
  .setColorForeground(c)
  .getCaptionLabel().setColor(t);
}

// MANUAL ENTRY
public String manualEntry() {
  return cP5.get(Textfield.class, "cmdEntry").getText().toUpperCase();
}

// UPDATE DIMENSIONS
public void updateDim(){
  String w_ = cP5.get(Textfield.class, "width").getText();
  String h_ = cP5.get(Textfield.class, "height").getText();
  String m_ = cP5.get(Textfield.class, "margin").getText();

  canvas_width = (w_ != "" && PApplet.parseFloat(w_)>0) ? PApplet.parseFloat(w_) : canvas_width;
  canvas_height = (h_ != "" && PApplet.parseFloat(h_)>0) ? PApplet.parseFloat(h_) : canvas_height;
  canvas_margin = (m_ != "" && PApplet.parseFloat(m_)<canvas_width/2 && PApplet.parseFloat(m_)<canvas_height/2) ? PApplet.parseFloat(m_) : canvas_margin;
}

// UPDATE SPEED
public void updateSpeed(){
  String s_ = cP5.get(Textfield.class, "speed").getText();
  spray_speed = (s_ != "" && PApplet.parseFloat(s_)>0 && PApplet.parseFloat(s_)<=10000) ? PApplet.parseFloat(s_) : spray_speed;
  send("G1F"+str(spray_speed)+"\n");
}

////////////////////////////////////////////////////////////////////////////////
// SERIAL COMMUNICATION
////////////////////////////////////////////////////////////////////////////////

// OPEN SERIAL PORT
public void openSerial(){
  if( portname == null ){
    connected = false;
    return;
  }
  if( port != null ) port.stop();

  port = new Serial(this, portname, 115200);
  port.bufferUntil('\n');
  connected = true;
}

// SELECT SERIAL PORT
// void serialEvent(Serial s){
//   String temp = port.readStringUntil('\n');
//   if (temp == null) return;
//   if (extractDim( temp )) return;
//   // if (val != temp) print( temp + "\n");
//   if (temp.startsWith("ok")){
//     if (poll > 0){
//       // print(poll+"\n");
//       poll--;
//       return;
//     }
//     if (streaming) line++;
//     stream();
//   }
// }

public void selectSerial(){
  int s = Serial.list().length;
  if( s == 0 ){
    JOptionPane.showMessageDialog(null, "No Arduino Connected");
    return;
  }
  if( s > 1){
    String result = (String) JOptionPane.showInputDialog(
      null,
      "Select the serial port connected to Arduino",
      "Select serial port",
      JOptionPane.PLAIN_MESSAGE,
      null,
      Serial.list(),
      0
    );
    if( result != null ) portname = result;
  }
  else portname = Serial.list()[0];
  openSerial();
}

public void serialRun(){
  if(port.available() > 0){
    String temp = port.readStringUntil('\n');
    // print( temp );
    if (temp == null) return;
    if (extractDim( temp )) return;
    // if (val != temp) print( temp + "\n");
    if (temp.startsWith("ok")){
      // if (poll > 0){
        // print(poll+"\n");
        // poll--;
        // return;
      // }
      if (streaming) line++;
      stream();
    }
    // if (streaming){
    //   if(temp.startsWith("ok")) line++;
    //   stream();
    // }
    // val = temp;
    // print( val + "\n" );
  }
}

public void statusReport(boolean p){
  port.write( report() + "\n" );
}

public boolean extractDim( String v ){
  String regex = "<*>".replaceAll(".","[$0]").replace("[*]",".*");
  if (v == null) return false;
  if ( !v.matches(regex) ) return false;
  println(v);
  int startChar = v.indexOf("WPos:")+5;
  int endChar = v.indexOf("|", startChar);
  // int endChar = v.indexOf(">", startChar);
  if( startChar < 0 || endChar < 0) return true;
  String[] p_ = v.substring(startChar,endChar).split(",");
  posx = PApplet.parseFloat(p_[0]);
  posy = PApplet.parseFloat(p_[1]);
  startChar = v.indexOf("FS:")+3;
  endChar = v.indexOf("|", startChar);
  if( startChar < 0 || endChar < 0) return true;
  int servo = PApplet.parseInt( v.substring(startChar,endChar).split(",")[1]);
  spraying = (servo == sprayon);
  return true;
}

// SERIAL SEND
public void send( String cmd ){
  if(!connected) return;
  sent = join(cmd.split("\n")," ");
  port.write(cmd.trim().replace(" ","") + "\n");

  if( VERBOSE ) print("SENT: " + cmd + '\n');
}

// SERIAL SEND BYTE
public void sendByte( Byte b ){
  if(!connected) return;
  port.write( b );
}

// SERIAL STREAM
public void stream(){
  if(!connected || !streaming) return;

  while(true){
    if( line == gcode.size() ){
      print("COMPLETED\n");
      streaming = false;
      return;
    }
    if( gcode.get(line).trim().length() == 0 ){
      line++;
      continue;
    }
    else break;
  }
  print(line + " " + gcode.get(line) + "\n");
  port.write(gcode.get(line).trim().replace(" ","") + "\n");
}
  static public void main(String[] passedArgs) {
    String[] appletArgs = new String[] { "portrait_bot_v2" };
    if (passedArgs != null) {
      PApplet.main(concat(appletArgs, passedArgs));
    } else {
      PApplet.main(appletArgs);
    }
  }
}
