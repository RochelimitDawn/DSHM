#ifndef SILICONLEAP_PTY_COMPAT_H
#define SILICONLEAP_PTY_COMPAT_H

/* Android bionic 缺失 <pty.h> 中的 openpty / forkpty / login_tty，
   此处用 posix_openpt 系列实现等价功能，供 node-pty 交叉编译时使用。 */

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static inline int siliconleap_openpty(int *amaster, int *aslave, char *name,
                                      const struct termios *termp,
                                      const struct winsize *winp) {
  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) return -1;
  if (grantpt(master) != 0) {
    close(master);
    return -1;
  }
  if (unlockpt(master) != 0) {
    close(master);
    return -1;
  }
  char *nameptr = ptsname(master);
  if (nameptr == NULL) {
    close(master);
    return -1;
  }
  int slave = open(nameptr, O_RDWR | O_NOCTTY);
  if (slave < 0) {
    close(master);
    return -1;
  }
  if (termp) tcsetattr(slave, TCSAFLUSH, termp);
  if (winp) ioctl(slave, TIOCSWINSZ, winp);
  if (name) strcpy(name, nameptr);
  *amaster = master;
  *aslave = slave;
  return 0;
}

static inline pid_t siliconleap_forkpty(int *amaster, char *name,
                                        const struct termios *termp,
                                        const struct winsize *winp) {
  int master = -1;
  int slave = -1;
  if (siliconleap_openpty(&master, &slave, name, termp, winp) != 0) return -1;
  pid_t pid = fork();
  if (pid < 0) {
    close(master);
    close(slave);
    return -1;
  }
  if (pid == 0) {
    close(master);
    setsid();
    ioctl(slave, TIOCSCTTY, 0);
    dup2(slave, 0);
    dup2(slave, 1);
    dup2(slave, 2);
    if (slave > 2) close(slave);
    return 0;
  }
  close(slave);
  *amaster = master;
  return pid;
}

static inline int siliconleap_login_tty(int fd) {
  setsid();
  if (ioctl(fd, TIOCSCTTY, 0) == -1) return -1;
  dup2(fd, 0);
  dup2(fd, 1);
  dup2(fd, 2);
  if (fd > 2) close(fd);
  return 0;
}

#if defined(__ANDROID__)
#define openpty siliconleap_openpty
#define forkpty siliconleap_forkpty
#define login_tty siliconleap_login_tty
#endif

#endif /* SILICONLEAP_PTY_COMPAT_H */
