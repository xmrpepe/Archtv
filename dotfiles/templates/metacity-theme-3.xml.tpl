<?xml version="1.0"?>
<!-- ArchDock {{ theme_name }}: flat Omarchy-style Marco frames. Rendered by archdock-theme-set.
     Slim title bar in the window background colour, a 2px accent border on the
     focused window (Omarchy's active border), and small round buttons. -->
<metacity_theme>
<info>
  <name>ArchDock-{{ theme_id }}</name>
  <author>ArchDock</author>
  <copyright>GPL-3.0 (palette from Omarchy, MIT)</copyright>
  <date>2026</date>
  <description>Omarchy {{ theme_name }} for Marco</description>
</info>

<constant name="C_BG" value="{{ background }}"/>
<constant name="C_BG_DARK" value="{{ dark_background }}"/>
<constant name="C_FG" value="{{ foreground }}"/>
<constant name="C_FG_DIM" value="{{ dark_foreground }}"/>
<constant name="C_ACCENT" value="{{ accent }}"/>
<constant name="C_INACTIVE" value="{{ selection }}"/>
<constant name="C_CLOSE" value="{{ red }}"/>
<constant name="C_MAX" value="{{ green }}"/>
<constant name="C_MIN" value="{{ yellow }}"/>
<constant name="C_HOVER" value="{{ bright_foreground }}"/>

<frame_geometry name="normal" title_scale="medium" rounded_top_left="false" rounded_top_right="false">
  <distance name="left_width" value="2"/>
  <distance name="right_width" value="2"/>
  <distance name="bottom_height" value="2"/>
  <distance name="left_titlebar_edge" value="10"/>
  <distance name="right_titlebar_edge" value="10"/>
  <distance name="title_vertical_pad" value="9"/>
  <aspect_ratio name="button" value="1.0"/>
  <border name="title_border" left="10" right="10" top="2" bottom="2"/>
  <border name="button_border" left="3" right="3" top="7" bottom="7"/>
</frame_geometry>

<frame_geometry name="max" parent="normal" rounded_top_left="false" rounded_top_right="false">
  <distance name="left_width" value="0"/>
  <distance name="right_width" value="0"/>
  <distance name="bottom_height" value="0"/>
  <distance name="left_titlebar_edge" value="6"/>
  <distance name="right_titlebar_edge" value="6"/>
</frame_geometry>

<frame_geometry name="border" has_title="false">
  <distance name="left_width" value="2"/>
  <distance name="right_width" value="2"/>
  <distance name="bottom_height" value="2"/>
  <distance name="left_titlebar_edge" value="0"/>
  <distance name="right_titlebar_edge" value="0"/>
  <distance name="title_vertical_pad" value="0"/>
  <border name="title_border" left="0" right="0" top="0" bottom="0"/>
  <border name="button_border" left="0" right="0" top="0" bottom="0"/>
  <distance name="button_width" value="0"/>
  <distance name="button_height" value="0"/>
</frame_geometry>

<!-- Frame backgrounds -->
<draw_ops name="frame_focused">
  <rectangle color="C_ACCENT" x="0" y="0" width="width" height="height" filled="true"/>
  <rectangle color="C_BG" x="2" y="2" width="width - 4" height="height - 4" filled="true"/>
</draw_ops>
<draw_ops name="frame_unfocused">
  <rectangle color="C_INACTIVE" x="0" y="0" width="width" height="height" filled="true"/>
  <rectangle color="C_BG" x="2" y="2" width="width - 4" height="height - 4" filled="true"/>
</draw_ops>
<draw_ops name="frame_max">
  <rectangle color="C_BG" x="0" y="0" width="width" height="height" filled="true"/>
</draw_ops>

<!-- Titles -->
<draw_ops name="title_focused">
  <title color="C_FG" x="(width - title_width) / 2 `max` 0" y="(height - title_height) / 2 `max` 0"/>
</draw_ops>
<draw_ops name="title_unfocused">
  <title color="C_FG_DIM" x="(width - title_width) / 2 `max` 0" y="(height - title_height) / 2 `max` 0"/>
</draw_ops>

<!-- Buttons: small dots, brighter on hover -->
<draw_ops name="dot_close"><arc color="C_CLOSE" x="width/2 - 6" y="height/2 - 6" width="12" height="12" start_angle="0" extent_angle="360" filled="true"/></draw_ops>
<draw_ops name="dot_max"><arc color="C_MAX" x="width/2 - 6" y="height/2 - 6" width="12" height="12" start_angle="0" extent_angle="360" filled="true"/></draw_ops>
<draw_ops name="dot_min"><arc color="C_MIN" x="width/2 - 6" y="height/2 - 6" width="12" height="12" start_angle="0" extent_angle="360" filled="true"/></draw_ops>
<draw_ops name="dot_hover"><arc color="C_HOVER" x="width/2 - 6" y="height/2 - 6" width="12" height="12" start_angle="0" extent_angle="360" filled="true"/></draw_ops>
<draw_ops name="dot_idle"><arc color="C_INACTIVE" x="width/2 - 6" y="height/2 - 6" width="12" height="12" start_angle="0" extent_angle="360" filled="true"/></draw_ops>
<draw_ops name="blank"/>

<frame_style name="base" geometry="normal">
  <piece position="entire_background" draw_ops="frame_focused"/>
  <piece position="title" draw_ops="title_focused"/>
  <button function="close" state="normal" draw_ops="dot_close"/>
  <button function="close" state="prelight" draw_ops="dot_hover"/>
  <button function="close" state="pressed" draw_ops="dot_hover"/>
  <button function="maximize" state="normal" draw_ops="dot_max"/>
  <button function="maximize" state="prelight" draw_ops="dot_hover"/>
  <button function="maximize" state="pressed" draw_ops="dot_hover"/>
  <button function="minimize" state="normal" draw_ops="dot_min"/>
  <button function="minimize" state="prelight" draw_ops="dot_hover"/>
  <button function="minimize" state="pressed" draw_ops="dot_hover"/>
  <button function="menu" state="normal" draw_ops="blank"/>
  <button function="menu" state="prelight" draw_ops="blank"/>
  <button function="menu" state="pressed" draw_ops="blank"/>
  <button function="shade" state="normal" draw_ops="blank"/>
  <button function="shade" state="prelight" draw_ops="blank"/>
  <button function="shade" state="pressed" draw_ops="blank"/>
  <button function="unshade" state="normal" draw_ops="blank"/>
  <button function="unshade" state="prelight" draw_ops="blank"/>
  <button function="unshade" state="pressed" draw_ops="blank"/>
  <button function="above" state="normal" draw_ops="blank"/>
  <button function="above" state="prelight" draw_ops="blank"/>
  <button function="above" state="pressed" draw_ops="blank"/>
  <button function="unabove" state="normal" draw_ops="blank"/>
  <button function="unabove" state="prelight" draw_ops="blank"/>
  <button function="unabove" state="pressed" draw_ops="blank"/>
  <button function="stick" state="normal" draw_ops="blank"/>
  <button function="stick" state="prelight" draw_ops="blank"/>
  <button function="stick" state="pressed" draw_ops="blank"/>
  <button function="unstick" state="normal" draw_ops="blank"/>
  <button function="unstick" state="prelight" draw_ops="blank"/>
  <button function="unstick" state="pressed" draw_ops="blank"/>
</frame_style>

<frame_style name="unfocused" parent="base">
  <piece position="entire_background" draw_ops="frame_unfocused"/>
  <piece position="title" draw_ops="title_unfocused"/>
  <button function="close" state="normal" draw_ops="dot_idle"/>
  <button function="maximize" state="normal" draw_ops="dot_idle"/>
  <button function="minimize" state="normal" draw_ops="dot_idle"/>
</frame_style>

<frame_style name="max_focused" parent="base" geometry="max">
  <piece position="entire_background" draw_ops="frame_max"/>
</frame_style>
<frame_style name="max_unfocused" parent="unfocused" geometry="max">
  <piece position="entire_background" draw_ops="frame_max"/>
</frame_style>

<frame_style name="border_focused" geometry="border">
  <piece position="entire_background" draw_ops="frame_focused"/>
</frame_style>
<frame_style name="border_unfocused" geometry="border">
  <piece position="entire_background" draw_ops="frame_unfocused"/>
</frame_style>

<frame_style_set name="normal">
  <frame focus="yes" state="normal" resize="both" style="base"/>
  <frame focus="no" state="normal" resize="both" style="unfocused"/>
  <frame focus="yes" state="maximized" style="max_focused"/>
  <frame focus="no" state="maximized" style="max_unfocused"/>
  <frame focus="yes" state="shaded" style="base"/>
  <frame focus="no" state="shaded" style="unfocused"/>
  <frame focus="yes" state="maximized_and_shaded" style="max_focused"/>
  <frame focus="no" state="maximized_and_shaded" style="max_unfocused"/>
</frame_style_set>

<frame_style_set name="border">
  <frame focus="yes" state="normal" resize="both" style="border_focused"/>
  <frame focus="no" state="normal" resize="both" style="border_unfocused"/>
  <frame focus="yes" state="maximized" style="border_focused"/>
  <frame focus="no" state="maximized" style="border_unfocused"/>
  <frame focus="yes" state="shaded" style="border_focused"/>
  <frame focus="no" state="shaded" style="border_unfocused"/>
  <frame focus="yes" state="maximized_and_shaded" style="border_focused"/>
  <frame focus="no" state="maximized_and_shaded" style="border_unfocused"/>
</frame_style_set>

<window type="normal" style_set="normal"/>
<window type="dialog" style_set="normal"/>
<window type="modal_dialog" style_set="normal"/>
<window type="menu" style_set="normal"/>
<window type="utility" style_set="normal"/>
<window type="border" style_set="border"/>
</metacity_theme>
