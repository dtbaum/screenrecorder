/*
 * Copyright (c) 2020 Dimitri Tenenbaum All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */
package org.jenkinsci.plugins.screenrecorder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import com.google.common.io.CharStreams;

import hudson.Extension;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.QuotedStringTokenizer;
import jenkins.util.VirtualFile;
import net.sf.json.JSONObject;

public class ScreenRecorderBuildWrapper extends BuildWrapper implements Serializable
{
  private static final long serialVersionUID = 4507500219846883170L;
  private String outVideoFileName = "${WORKSPACE}/${JOB_NAME}_${BUILD_NUMBER}.mp4";

  //  JENKINS_HOME
  private Boolean failJobIfFailed = true;
  private String ffmpegCommand = "";

  private transient Launcher launcher;
  private transient Proc ffmpegProc;

  @DataBoundConstructor
  public ScreenRecorderBuildWrapper( String ffmpegCommand, Boolean failJobIfFailed)
  {
    this.setFfmpegCommand(ffmpegCommand);
    this.setFailJobIfFailed(failJobIfFailed);
  }

  public Boolean getFailJobIfFailed()
  {
    return failJobIfFailed;
  }
  
  
  public String getOutVideoFileName()
  {
    return outVideoFileName;
  }

  public void setOutVideoFileName(String outFileName)
  {
    this.outVideoFileName = outFileName;
  }

  public void setFailJobIfFailed(Boolean failJobIfFailed)
  {
    if (failJobIfFailed == null)
      this.failJobIfFailed = true;
    else
      this.failJobIfFailed = failJobIfFailed;
  }


  @Override
  public Environment setUp(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher_,
      final BuildListener listener) throws IOException, InterruptedException
  {
    Thread.sleep(3000);//time to setup Xvnc plugin
    launcher = launcher_;
    DescriptorImpl DESCRIPTOR = Hudson.getInstance().getDescriptorByType(DescriptorImpl.class);
    final Date from = new Date();
    String myFfmpegCommand = Util.nullify(getFfmpegCommand());
    myFfmpegCommand = Util.replaceMacro(myFfmpegCommand, build.getEnvironment(listener));
    String outFilePath = Util.replaceMacro(outVideoFileName, build.getEnvironment(listener));
    File artifactsDir = build.getArtifactsDir();
    listener.getLogger().print(build.getUrl());
    if (!artifactsDir.exists())
    {
      if (!artifactsDir.mkdir())
      {
        listener.error("Can't create " + artifactsDir.getAbsolutePath());
      }
    }
    if (outFilePath == null || outFilePath.toLowerCase().contains("null"))
    {
      outFilePath = build.getNumber() + ".mp4";

    }
    final File outVideoFile = new File(outFilePath);
   
    myFfmpegCommand += " " + outFilePath;
    try
    {
      ProcStarter proc = launcher.launch().cmds( QuotedStringTokenizer.tokenize(myFfmpegCommand));
      proc.writeStdin();
      //proc.readStderr();
      ffmpegProc = proc.start();
      String curCsp = System.getProperty("hudson.model.DirectoryBrowserSupport.CSP","");
      if (!curCsp.contains("media-src"))
      {
        System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", curCsp + ";media-src 'self';");//TODO: as info in the console
        listener.getLogger().println("Enabling embedded video: adding media-src 'self' to hudson.model.DirectoryBrowserSupport.CSP property");
        listener.getLogger().println("Old value: " + curCsp);
        listener.getLogger().println("New value: " + System.getProperty("hudson.model.DirectoryBrowserSupport.CSP","") + "\n");
      }
      //listener.getLogger().print("Writing html file to " + outFileHtml.getAbsolutePath());
      //writeHtmlFile(outVideoFile.getName(), outFileHtml, outVideoFile.getName());
    }
    catch (Exception e)
    {
      listener.getLogger().println(e.getMessage());
      if (failJobIfFailed) 
      {
        throw e;
      }
    }
    return new Environment()
    {
      @Override
      public void buildEnvVars(Map<String, String> env)
      {
      }

      @Override
      public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException
      {
        String fname =  Util.replaceMacro("${JOB_NAME}_${BUILD_NUMBER}.mp4", build.getEnvironment(listener));
        InputStreamReader r1 = null;
        InputStreamReader r2 = null;
        try
        {
          if (ffmpegProc != null 
              && ffmpegProc.getStderr() != null
              && ffmpegProc.getStdout() != null
              && !build.getWorkspace().child(fname).exists() )
          {
            r1 = new  InputStreamReader(ffmpegProc.getStderr(), "UTF-8" ) ;
            r2 = new  InputStreamReader(ffmpegProc.getStdout(), "UTF-8" )  ;
            String err = CharStreams.toString(r1);
            String out = CharStreams.toString(r2);
            listener.getLogger().println("Video recording failed: ");
            String[] arr = out.split(System.lineSeparator());
            if (arr.length > 1) 
            {
              err += arr[arr.length- 2] + System.lineSeparator();
            }
            if (out.length() > 0) 
            {
              err += arr[arr.length- 1] + System.lineSeparator();
            }
            listener.getLogger().println(err);        
          }
        }
        catch (Exception e)
        {
          listener.getLogger().println(e.getMessage());  
        }
        finally 
        {
          if (r1 != null)
            r1.close();
          if (r2 != null)
            r1.close();
        }
        if (ffmpegProc != null && ffmpegProc.isAlive())
        {
          ffmpegProc.getStdin().write(("q" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
          ffmpegProc.getStdin().flush();
          ffmpegProc.getStdin().close();
          final Date to = new Date();
          Thread.sleep(1000);//ffmpeg needs some more time for large videos
          SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd 'T' HH:mm:ss");
          final Map<String, String> artifacts = Collections.singletonMap(fname, build.getWorkspace().child(fname).getName());
          build.getArtifactManager().archive(build.getWorkspace(), launcher, listener, artifacts);
          VirtualFile archivedVideo = build.getArtifactManager().root().child(fname);
          if (archivedVideo.length() == build.getWorkspace().child(fname).length())
          {
            build.getWorkspace().child(fname).delete();
          }
          String htmlFileName =  Util.replaceMacro(
              "${JENKINS_HOME}/jobs/${JOB_NAME}/builds/${BUILD_NUMBER}/archive/${JOB_NAME}_${BUILD_NUMBER}.html",
              build.getEnvironment(listener));
          final File outFileHtml = new File(htmlFileName);
          writeHtmlFile(outVideoFile.getName(), outFileHtml, outVideoFile.getName());
          listener.hyperlink("artifact/" + outFileHtml.getName(),
              "Video from " + sf.format(from) + " to " + sf.format(to));
          listener.getLogger().print("\n");
          return true;
        }
        else 
        {
          String myFfmpegCommand = Util.nullify(getFfmpegCommand());
          myFfmpegCommand = Util.replaceMacro(myFfmpegCommand, build.getEnvironment(listener));
          listener.getLogger().println("ScreenRecorder: video recording failed, try to run '" + myFfmpegCommand + "' on the command line, in the target system");
          
          if (failJobIfFailed)
          {
            listener.getLogger().println("ScreenRecorder: video recording failed, fail the job due to failJobIfVideoRecordingFailed = true (see job config)");
            return false;
          }
          else 
          {
            listener.getLogger().println("ScreenRecorder: video recording failed, don't fail the job due to failJobIfVideoRecordingFailed = true (see job config)");
            return true;
          }
        }
      }
    };
  }

  private void writeHtmlFile(String title, File htmlFile, String videoFile) throws IOException
  {
    String txt = "<html>\n" + "<head>\n"
        + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self'; script-src 'self'\">\n"
        + " <meta http-equiv=\"X-Content-Security-Policy\" content=\"default-src 'self'; script-src 'self'\">\n"
        + " <meta http-equiv=\"X-WebKit-CSP\" content=\"default-src 'self'; script-src 'self'\">\n"
        + "<title>" + title +"</title>\n" + "</head>\n" + "\n" + "<body>\n" + "<video src=\"" + videoFile
        + "\" controls>\n" + "</video>\n" + "</body>\n" + "\n" + "</html>";

    BufferedWriter writer = new BufferedWriter(new FileWriter(htmlFile));
    writer.write(txt);
    writer.close();
  }

  public String getFfmpegCommand()
  {
    DescriptorImpl DESCRIPTOR = Hudson.getInstance().getDescriptorByType(DescriptorImpl.class);
    if (ffmpegCommand == null || ffmpegCommand.isEmpty())
    {
      return Hudson.getInstance().getDescriptorByType(DescriptorImpl.class).getFfmpegDefaultCommand();
    }
    return ffmpegCommand;
  }

  public void setFfmpegCommand(String ffmpegCommand)
  {
    this.ffmpegCommand = ffmpegCommand;
  }

  @Extension(ordinal = -1)
  public static final class DescriptorImpl extends BuildWrapperDescriptor
  {
    private String  ffmpegDefaultCommand = "ffmpeg -video_size 1920x1080 -framerate 25 -f x11grab -i :0.0";
    public DescriptorImpl()
    {
      super(ScreenRecorderBuildWrapper.class);
      load();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException
    {
      req.bindJSON(this, json);
      save();
      return true;
    }

    @Override
    public String getDisplayName()
    {
      return "Record screen with FFmpeg";
    }

    @Override
    public boolean isApplicable(AbstractProject<?, ?> item)
    {
       return true;
    }

    public String getFfmpegDefaultCommand()
    {
      return ffmpegDefaultCommand;
    }

    public void setFfmpegDefaultCommand(String ffmpegDefaultCommand)
    {
      this.ffmpegDefaultCommand = ffmpegDefaultCommand;
    }
  }
}
