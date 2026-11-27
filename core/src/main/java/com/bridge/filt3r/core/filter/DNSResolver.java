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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.bridge.filt3r.core.util.ExecutionEnvironment;
import com.bridge.filt3r.core.util.Logger;
import com.bridge.filt3r.core.util.PatternSequence;

/**
 * Filters one DNS query: answers it locally (custom mapping or blocked host)
 * or forwards it to the upstream and patches the response.
 * Input and output are plain DNS messages without any IP or UDP header.
 */
public class DNSResolver {

	/** Sends a DNS query to the real resolver and returns its response. */
	public interface Upstream {
		byte[] query(byte[] request) throws IOException;
	}

	private static final int MAX_LOCAL_RESPONSE = 4096;
	private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
	private static final int NXDOMAIN = 3;
	private static final int REFUSED = 5;

	private static boolean enableLocalResolver = false;
	private static int localResolverTTL = 0;
	private static PatternSequence customIPMappings = null;

	private static final int TYPE_AAAA = 28;
	private volatile boolean allowIPv6 = true;
	private final Upstream upstream;

	public DNSResolver(Upstream upstream) {
		this.upstream = upstream;
	}

	public static void initLocalResolver(PatternSequence customMappings, boolean enabled, int ttl){
		customIPMappings = customMappings;
		localResolverTTL= ttl;
		enableLocalResolver = enabled;
	}

	/** When off, AAAA queries get an empty answer so apps use IPv4 only. */
	public void setAllowIPv6(boolean allow) {
		this.allowIPv6 = allow;
	}

	/** The query's header and question only, flagged as a response with the given RCODE (0 NOERROR, 3 NXDOMAIN). */
	private static byte[] emptyAnswer(byte[] request, int rcode) throws IOException {
		int pos = 12;
		while (pos < request.length && request[pos] != 0)
			pos += (request[pos] & 0xFF) + 1;
		int end = pos + 1 + 4; // root label, QTYPE, QCLASS
		if (pos >= request.length || end > request.length)
			throw new IOException("malformed question");
		byte[] r = Arrays.copyOf(request, end);
		r[2] = (byte) (0x80 | (request[2] & 0x01)); // QR, keep RD, opcode 0
		r[3] = (byte) (0x80 | rcode);                // RA, RCODE
		r[4] = 0; r[5] = 1;                          // one question
		r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0;
		return r;
	}

	/** A refusal (RCODE 5) for a query: what a blocked app's DNS question gets. */
	public static byte[] refused(byte[] request) throws IOException {
		return emptyAnswer(request, REFUSED);
	}

	/**
	 * @param clientID label for the query log, for example "10.0.0.2:5353"
	 * @param request  one DNS query message
	 * @return the DNS response message, or null if the input was a response and not a query
	 */
	public byte[] resolve(String clientID, byte[] request) throws IOException {
		IN_FLIGHT.incrementAndGet();
		try {
			DatagramPacket req = new DatagramPacket(request, 0, request.length);
			SimpleDNSMessage query = getDNSQuery(clientID, req);
			if (query == null)
				return null;
			if (!allowIPv6 && query.qType == TYPE_AAAA)
				return emptyAnswer(request, 0);

			byte[] buf = new byte[MAX_LOCAL_RESPONSE];
			DatagramPacket local = new DatagramPacket(buf, 0, buf.length);
			if (resolveLocal(clientID, query, local))
				return Arrays.copyOf(buf, local.getLength());

			if (query.isStandardQuery() && isLocalName(query.qHost)) {
				DNSResponsePatcher.trafficLog(clientID, query.qClass, query.qType, query.qHost, null, 0);
				return emptyAnswer(request, NXDOMAIN);
			}

			byte[] response = upstream.query(request);
			DNSResponsePatcher.patchResponse(clientID, response, 0); // patches in place
			return response;
		} finally {
			IN_FLIGHT.decrementAndGet();
		}
	}

	/**
	 * Names for local networks: the standard ones (RFC 6762, 8375, 6303), the ones home routers use, and the
	 * reverse zones of the private IPv4 ranges and the IPv6 local ranges. "localhost" is left out on purpose.
	 */
	private static final String[] LOCAL_SUFFIXES = {
			"local", "home.arpa", "internal", "lan", "home", "intranet", "corp", "private",
			"10.in-addr.arpa", "168.192.in-addr.arpa", "254.169.in-addr.arpa",
			"16.172.in-addr.arpa", "17.172.in-addr.arpa", "18.172.in-addr.arpa", "19.172.in-addr.arpa",
			"20.172.in-addr.arpa", "21.172.in-addr.arpa", "22.172.in-addr.arpa", "23.172.in-addr.arpa",
			"24.172.in-addr.arpa", "25.172.in-addr.arpa", "26.172.in-addr.arpa", "27.172.in-addr.arpa",
			"28.172.in-addr.arpa", "29.172.in-addr.arpa", "30.172.in-addr.arpa", "31.172.in-addr.arpa",
			"d.f.ip6.arpa", "8.e.f.ip6.arpa", "9.e.f.ip6.arpa", "a.e.f.ip6.arpa", "b.e.f.ip6.arpa"};

	/** True for a name that only means something on a local network: asking a public resolver would only leak it. */
	private static boolean isLocalName(String host) {
		String name = host.toLowerCase(Locale.ROOT);
		for (String suffix : LOCAL_SUFFIXES) {
			if (name.equals(suffix) || name.endsWith("." + suffix))
				return true;
		}
		return false;
	}

	public static int getResolverCount() {
		return IN_FLIGHT.get();
	}

	public boolean resolveLocal(String client, SimpleDNSMessage dnsQuery, DatagramPacket response) throws IOException {

		if (!enableLocalResolver)
			return false;

		if (!dnsQuery.isStandardQuery())
			return false;

		Object[] info = dnsQuery.getQueryData();

		short type = (short) info[1];
		short clss = (short) info[2];

		if(type != 1 && type != 28)
			return handle_NonTyp_1_28(client, dnsQuery, response);

		String host = (String) info[0];
		byte[] ip = null;
		String prfx = ">4";
		byte[] filterIP = DNSResponsePatcher.ipv4_blocked;
		if (type == 28) {
			prfx = ">6";
			filterIP = DNSResponsePatcher.ipv6_blocked;
		}

		if (customIPMappings != null)
			ip = (byte[]) customIPMappings.match(prfx+host.toLowerCase());
		if (ip == null && DNSResponsePatcher.filter(host, false)) {
			DNSResponsePatcher.logNstats(true, host);
			ip = filterIP;
		}
		if (ip != null) {

			DNSResponsePatcher.trafficLog(client,clss,type,host,null,0);
			int length = dnsQuery.produceResponse(response.getData(), response.getOffset(), ip, localResolverTTL);
			response.setLength(length);

			String addrStr = InetAddress.getByAddress(ip).getHostAddress().toString();

			DNSResponsePatcher.trafficLog(client,clss,type,host, addrStr, ip.length);

			if (ip != filterIP) {
				Logger.getLogger().logLine("MAPPED_CUSTOM_IP: "+host+"->"+addrStr);
				DNSResponsePatcher.logNstats(false, host); // a custom answer is an allowed query; the query log lists it
			}
			
			return true;
		} else
			return false;
	}

	private boolean handle_NonTyp_1_28(String client, SimpleDNSMessage dnsQuery, DatagramPacket response) {
		//Logger.getLogger().logLine("TYPE 65");
		String host = dnsQuery.qHost;
		if (!DNSResponsePatcher.filter(host, false))
			return false;
		DNSResponsePatcher.trafficLog(client, dnsQuery.qClass, dnsQuery.qType, host,null,0);
		DNSResponsePatcher.logNstats(true, host);
		int length = dnsQuery.get_NonTyp_1_28_FilterResponse(response.getData(), response.getOffset());
		response.setLength(length);
		return true;
	}

	public SimpleDNSMessage getDNSQuery(String client, DatagramPacket request) throws IOException {
		SimpleDNSMessage dnsQuery = null;
		try {
			dnsQuery = new SimpleDNSMessage(request.getData(), request.getOffset(), request.getLength());
		} catch (Exception e){
			if (ExecutionEnvironment.getEnvironment().debug()) {
				File dump = new File(ExecutionEnvironment.getEnvironment().getWorkDir() + "/dnsdump_" + System.currentTimeMillis());
				FileOutputStream dumpout = new FileOutputStream(dump);
				dumpout.write(request.getData(), request.getOffset(), request.getLength());
				dumpout.flush();
				dumpout.close();
			}
			Logger.getLogger().logException(e);
			throw new IOException(e);
		}

		if (dnsQuery.isResponse()) {
			if (ExecutionEnvironment.getEnvironment().debug())
				Logger.getLogger().logLine("Skipping response record, expected query record!");
			return null; //not a query!
		}
		return dnsQuery;
	}
}
