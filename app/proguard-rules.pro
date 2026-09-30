# Media3 / ExoPlayer keep rules are only needed if release minification is enabled.
# v1 ships with minify off. If you enable R8, start from the Media3 consumer rules
# shipped inside the ExoPlayer AAR and keep decoder class names used by reflection.
