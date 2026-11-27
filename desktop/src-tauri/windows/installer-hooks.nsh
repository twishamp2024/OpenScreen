; OpenScreen NSIS installer hooks (Tauri `bundle.windows.nsis.installerHooks`).
;
; Allows the phone to reach the signaling server on PRIVATE networks only
; (TCP 47820-47829, this executable only). Public networks stay closed by
; design. WebRTC media (UDP) needs no inbound rule: both sides send ICE
; checks, and Windows Firewall admits the replies to outbound packets.

!macro NSIS_HOOK_POSTINSTALL
  DetailPrint "Adding Windows Firewall rule for OpenScreen (Private networks only)"
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="OpenScreen signaling"'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="OpenScreen signaling" dir=in action=allow program="$INSTDIR\${MAINBINARYNAME}.exe" protocol=TCP localport=47820-47829 profile=private description="Lets your phone pair with OpenScreen on private networks."'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  DetailPrint "Removing OpenScreen Windows Firewall rule"
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="OpenScreen signaling"'
!macroend
