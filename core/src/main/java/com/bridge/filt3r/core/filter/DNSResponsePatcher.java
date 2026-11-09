/* 
 PersonalDNSFilter 1.5
 Copyright (C) 2017 Ingo Zenz

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 as published by the Free Software Foundation; either version 2
 of the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

 Find the latest version at http://www.zenz-solutions.de/personaldnsfilter
 Contact:i.z@gmx.net 
 */

package com.bridge.filt3r.core.filter;


import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Set;

import com.bridge.filt3r.core.util.Logger;
import com.bridge.filt3r.core.util.LoggerInterface;

public class DNSResponsePatcher {

	private static Set FILTER = null;
	private static LoggerInterface TRAFFIC_LOG = null;

	protected static byte[] ipv4_blocked;
	protected static byte[] ipv6_blocked;

	private static long okCnt=0;
	private static long filterCnt=0;
	private static boolean checkIP = false;
	private static boolean checkCNAME = true;
	private static volatile IpBlockList ipBlock;


	static {
		try {
			ipv4_blocked = InetAddress.getByName(ConfigurationAccess.getLocal().getConfig().getProperty("ipV4BlockedHost","127.0.0.1")).getAddress();
			ipv6_blocked = InetAddress.getByName(ConfigurationAccess.getLocal().getConfig().getProperty("ipV6BlockedHost","::1")).getAddress();
		} catch (Exception e) {
			Logger.getLogger().logException(e);
		}
	}


	public static void init(Set filter, LoggerInterface trafficLogger) {
		FILTER = filter;
		TRAFFIC_LOG = trafficLogger;
		//okCnt=0;
		//filterCnt=0;
		try {
			checkIP = Boolean.parseBoolean(ConfigurationAccess.getLocal().getConfig().getProperty("checkResolvedIP","false"));
			checkCNAME = Boolean.parseBoolean(ConfigurationAccess.getLocal().getConfig().getProperty("checkCNAME","false"));
		} catch (IOException e) {
			Logger.getLogger().logException(e);
		}
	}

	/** Answers that resolve to an address on this list are rewritten to the blocked address; null disables it. */
	public static void setIpBlockList(IpBlockList list) {
		ipBlock = list;
	}

	public static long getFilterCount() {
		return filterCnt;
	}

	public static long getOkCount() {
		return okCnt;
	}


	private static volatile long pausedUntilMs = 0;

	/**
	 * Until this wall-clock time (ms) every name and address is let through and logged as allowed; the
	 * tunnel stays up. 0 ends the pause. The deadline is checked on each query, so a sleeping phone or
	 * a late timer cannot keep filtering off past it. It lives in memory only, so a restart is never paused.
	 */
	public static void setPausedUntil(long epochMs) {
		pausedUntilMs = epochMs;
	}

	public static boolean isPaused() {
		long until = pausedUntilMs;
		return until != 0 && System.currentTimeMillis() < until;
	}

	public static byte[] patchResponse(String client, byte[] response, int offs) throws IOException {

		try {
			ByteBuffer buf = ByteBuffer.wrap(response, offs, response.length - offs);
			String queryHost = "";

			buf.getShort(); // ID
			buf.getShort(); // Flags
			int questCount = buf.getShort();
			int answerCount = buf.getShort();
			buf.getShort(); // auths
			buf.getShort(); // additional

			boolean filter = false;

			for (int i = 0; i < questCount; i++) {

				queryHost = readDomainName(buf, offs);
				short type = buf.getShort(); // query type

				//checking the filter on the answer does not always work due to cname redirects (type 5 responses)
				//therefore we just check the filter on the query host and thus we'll disallow also all cname redirects.
				//This seems to work well - however is not 100% correct!

				if (type == 1 || type == 28)
					filter = filter || filter(queryHost, true);

				short clss = buf.getShort(); // query class

				trafficLog(client, clss, type, queryHost,null,0);
			}

			for (int i = 0; i < answerCount; i++) {
				String host = readDomainName(buf, offs);
				short type = buf.getShort(); // type
				short clss = buf.getShort(); // class
				buf.getInt(); // TTL
				int len = buf.getShort() & 0xFFFF; // len (unsigned)
				if (len > buf.remaining())
					throw new IOException("DNS record is longer than the message");
				if ((type == 1 && len != 4) || (type == 28 && len != 16))
					throw new IOException("DNS address record has a bad length");

				boolean filtered = false;

				if ((type == 1 || type == 28)) {
					if (!filter && checkCNAME && !host.equalsIgnoreCase(queryHost)) { //avoid duplicate checking same hosts
						filter = filter || filter(host, true);  //Handle CNAME Cloaking!
						queryHost = host;
					}
					if (filter) {
						filtered = true;
						// replace ip!
						if (type == 1) // IPV4
							buf.put(ipv4_blocked);
						else if (type == 28) // IPV6
							buf.put(ipv6_blocked);
				} else if (!isPaused() && (checkIP || (ipBlock != null && (len == 4 || len == 16)))) { //check if resolved IP is filtered or on the IP blocklist
					byte[] answer = new byte[len];
					buf.get(answer);
					buf.position(buf.position() - len);
					InetAddress resolved = InetAddress.getByAddress(answer);
					IpBlockList list = ipBlock;
					if ((checkIP && filterIP(resolved.getHostAddress())) || (list != null && list.isBlocked(resolved))) {
						filtered = true;
						if (type == 1) // IPV4
							buf.put(ipv4_blocked);
						else if (type == 28) // IPV6
							buf.put(ipv6_blocked);
					}
				}
				}
				if (!filtered)
					buf.position(buf.position() + len); // go ahead

				//log answer
				if (TRAFFIC_LOG != null) {
					byte[] answer = new byte[len];
					String answerStr = null;
					buf.position(buf.position() - len);

					if (type == 5)
						answerStr = readDomainName(buf, offs);
					else {
						buf.get(answer);

						if (type == 1 || type == 28)
							answerStr = InetAddress.getByAddress(answer).getHostAddress();
						else
							answerStr = getReadableStringFromBinary(answer,0,answer.length);
					}
					trafficLog(client, clss, type, host, answerStr, len);
				}
			}
			return buf.array();
		} catch (IOException eio) {
			throw eio;
		} catch (Exception e){
			throw new IOException ("Invalid DNS response message structure", e);
		}
	}

	protected static boolean filter(String host, boolean log) {
		boolean result;

		if (FILTER == null || isPaused())
			result = false;
		else
			result = FILTER.contains(host);

		if (log)
			logNstats(result, host);

		return result;
	}


	protected static void trafficLog(String client, short clss, short type, String host, String answer, int length) {
		if (TRAFFIC_LOG == null)
			return;
		if (answer != null)
			TRAFFIC_LOG.logLine(client + ", "+ clss + ", A-" + type + ", " + host + ", " + answer + ", /Length:" + length);
		else
			TRAFFIC_LOG.logLine(client + ", "+ clss + ", Q-" + type + ", " + host + ", " + "<empty>");

	}

	/** Receives one call per A/AAAA query the filter decides on. */
	public interface QueryObserver {
		void onQuery(String host, boolean blocked);
	}

	private static volatile QueryObserver observer;

	public static void setObserver(QueryObserver o) {
		observer = o;
	}

	protected static void logNstats(boolean result, String host) {
		QueryObserver o = observer;
		if (o != null)
			o.onQuery(host, result);

		if (result == false)
			okCnt++;
		else
			filterCnt++;
	}


	private static boolean filterIP(String ip) {
		boolean result;

		if (FILTER == null)
			result = false;
		else
			result = FILTER.contains("%IP%"+ip);

		// no logging of the address here: the query log is the only place names are recorded

		if (!result)
			okCnt++;
		else
			filterCnt++;

		return result;
	}


	private static final int MAX_NAME_POINTER_JUMPS = 16;
	private static final int MAX_NAME_LENGTH = 255;

	/**
	 * Reads a DNS name. A compression pointer must point backwards, there are at most
	 * {@link #MAX_NAME_POINTER_JUMPS} of them, and the name is at most {@link #MAX_NAME_LENGTH} long;
	 * anything else is an {@link IOException}. This keeps a hostile packet from looping forever.
	 */
	protected static String readDomainName(ByteBuffer buf, int offs) throws IOException {

		byte[] substr = new byte[64];

		int count = -1;
		StringBuilder result = new StringBuilder();
		int ptrJumpPos = -1;
		int jumps = 0;

		while (count != 0) {
			count = buf.get();
			if (count != 0) {
				if ((count & 0xc0) == 0) {
					if (result.length() + 1 + count > MAX_NAME_LENGTH)
						throw new IOException("DNS name too long");
					buf.get(substr, 0, count);
					if (result.length() > 0)
						result.append('.');
					result.append(new String(substr, 0, count));
				} else {// pointer
					int pointerStart = buf.position() - 1;
					buf.position(pointerStart);
					int pointer = offs + (buf.getShort() & 0x3fff);
					if (++jumps > MAX_NAME_POINTER_JUMPS || pointer >= pointerStart || pointer < 0)
						throw new IOException("Bad DNS name pointer");
					if (ptrJumpPos == -1)
						ptrJumpPos = buf.position();
					buf.position(pointer);
				}
			} else {
				if (ptrJumpPos != -1)
					buf.position(ptrJumpPos);
			}
		}
		return result.toString();
	}

	public static String getReadableStringFromBinary(byte[] b, int offs, int r) {
		StringBuilder result = new StringBuilder();
		for (int i = offs; i < r; i++) {
			if (!((b[i] < 64 && b[i] > 32) || (b[i] < 91 && b[i] > 64) || (b[i] < 123 && b[i] > 96)))  {
				result.append((char) 46);
			}
			else if (b[i] != 44 && b[i] != 59)
				result.append((char) b[i]); //no comma or semicolon as it might serve as seperator
			else
				result.append((char) 46);
		}
		return result.toString();
	}
}

