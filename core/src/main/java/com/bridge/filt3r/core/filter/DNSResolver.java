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

	private static boolean enableLocalResolver = false;
	private static int localResolverTTL = 0;
	private static PatternSequence customIPMappings = null;

	private final Upstream upstream;

	public DNSResolver(Upstream upstream) {
		this.upstream = upstream;
	}

	public static void initLocalResolver(PatternSequence customMappings, boolean enabled, int ttl){
		customIPMappings = customMappings;
		localResolverTTL= ttl;
		enableLocalResolver = enabled;
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

			byte[] buf = new byte[MAX_LOCAL_RESPONSE];
			DatagramPacket local = new DatagramPacket(buf, 0, buf.length);
			if (resolveLocal(clientID, query, local))
				return Arrays.copyOf(buf, local.getLength());

			byte[] response = upstream.query(request);
			DNSResponsePatcher.patchResponse(clientID, response, 0); // patches in place
			return response;
		} finally {
			IN_FLIGHT.decrementAndGet();
		}
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

			if (ip != filterIP)
				Logger.getLogger().logLine("MAPPED_CUSTOM_IP: "+host+"->"+addrStr);
			
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
