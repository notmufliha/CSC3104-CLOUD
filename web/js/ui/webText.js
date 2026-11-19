// The few lines only the web version needs (things a browser can't do). Everything else uses the apps' shared table.
export const WEB = {
  desktopCall: (who) => `On a phone this would call ${who}.`,
  desktopSms: (who) => `On a phone this would open Messages to ${who}, with a note ready.`,
  copied: "Copied",
  copyText: "Copy text",
  copyFailed: "This browser didn't allow copying. Select the text and copy it.",
  reminderWeb: "In the web version the reminder shows when this page is open (on Sunday after 10 am). It needs HTTPS or localhost.",
  reminderDenied: "Notifications are off or not available for this page. They need HTTPS (or localhost) and permission in the browser.",
  frame: "Show phone frame",
  appearance: "Appearance",
};
