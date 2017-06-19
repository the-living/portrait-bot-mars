import processing.serial.*;

//String pattern = "t*e";
//String regex = "<*>".replaceAll(".","[$0]").replace("[*]",".*");
////println( regex );

//print( "<Idle|WPos:10.0,12.0,0.0|FS:200,200>".matches(regex) );


String fp = "gcode.txt";
String[] gcode;
int i = 0;
String lastSent = "";
Serial port;

int poll = -240;

StringList debug;

String echo = "*echo: *";
String status = "<*>";
String echoPattern, statusPattern;
String statusString = "...";
boolean match = false;
boolean ignore = false;

void setup(){
  size(400,100);
  gcode = loadStrings(fp);
  //print(gcode);
  
  port = new Serial(this, Serial.list()[0], 115200);
  port.bufferUntil('\n');
  delay(1000);
  
  debug = new StringList();
  echoPattern = echo.replaceAll(".","[$0]").replace("[*]",".*");
  statusPattern = status.replaceAll(".","[$0]").replace("[*]",".*");
}

void draw(){
  run();
  background(0);
  fill(255);
  text(statusString, 0, 20);

  if( poll == 10 ){
    port.write("?\n");
    poll=0;
  }
  poll++;
 
}

void run(){
  if( port.available() > 0 ){
    String temp = port.readStringUntil('\n');
    if( temp == null ) return;
    temp = temp.trim();
    if(temp.contains(":ok")) return;
    
    if( temp.matches(statusPattern)){
      statusString = temp;
      return;
    }
      
    print( temp + "\n" );
    debug.append("--" + temp + "--\n" );
    
    //check match
    //String pattern = echo.replaceAll(".","[$0]").replace("[*]",".*");
    //println( temp.matches(pattern) );
    //if( temp.matches(pattern) ){
    //  print( temp.substring(7) );
    //}
    String rx;
    
    
    if(temp.contains("[echo: ]")) ignore = true;
    else if( temp.matches(echoPattern) ){
      rx = temp.substring(7,temp.length()-1);
      debug.append( rx + "\n");
      if( rx.contains(lastSent) ){
        debug.append("matched");
        match = true;
      }
      else debug.append( "s:" + lastSent +"/ r:"+rx+"\\");
    }
    
    stream();
    if( temp.contains("ok") && !ignore && match ){
      debug.append("continuing\n");
      i++;
      match = false;
    } else {
      ignore = false;
    }
  }
}

void stream(){
  if (i == gcode.length) closeout();
  String cmd = gcode[i].trim().replace(" ","");
  if( !lastSent.contains(cmd) ){
    port.write( cmd + "\n" );
    lastSent = cmd;
    debug.append("SENDING "+i+":"+cmd+"\n");
    return;
  }
}

void closeout(){
  saveStrings( "debug.txt", debug.array() );
  exit();
}

void keyPressed() { 
     if (key == 27) {
       closeout();
     }
}