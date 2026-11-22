#!/usr/bin/perl
# MaterialCode tcpstates wrapper, installed over /usr/sbin/tcpstates-libbpf by
# `nookra-daemon guest fix-port-forwarding` (dpkg-divert keeps the real tool as
# tcpstates-libbpf.real; `--undo` puts it back).
#
# Linux Terminal's port forwarder (forwarder_guest_launcher) splits this tool's lines on
# whitespace and exits with "Invalid LPORT format" when a process or thread name has a space
# (Python 3.14 names threads "Thread-1 (run)"); the host then forwards no port until the guest
# restarts. This joins such names with "_" and passes every other line through.
use strict;
use warnings;
$| = 1;
open(my $tool, '-|', '/usr/bin/stdbuf', '-oL', '/usr/sbin/tcpstates-libbpf.real', @ARGV)
    or die "tcpstates-libbpf.real: $!";
# SKADDR PID COMM LADDR LPORT RADDR RPORT OLDSTATE -> NEWSTATE MS (-T adds a leading TIME).
my $lead = (grep { $_ eq '-T' || $_ eq '--timestamp' } @ARGV) ? 3 : 2;
while (my $line = <$tool>) {
    my @f = split ' ', $line;
    if (@f > $lead + 9 && $f[$#f - 2] eq '->') {
        my $comm = join '_', @f[$lead .. $#f - 8];
        $line = join(' ', @f[0 .. $lead - 1], $comm, @f[$#f - 7 .. $#f]) . "\n";
    }
    print $line;
}
close($tool);
exit($? >> 8);
