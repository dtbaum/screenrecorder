# screenrecorder
Jenkins plugin which records screen per FFmpeg and saves recorder mp4 video file as build artifact.
The mp4 video is also accessible per link from the console output of the build.
The default recording command "ffmpeg -video_size 1920x1080 -framerate 25 -f x11grab -i :0.0" can be customized in the configuration of the job.
Requirements: installed FFmpeg.
