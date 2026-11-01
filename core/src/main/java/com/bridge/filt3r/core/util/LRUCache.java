 /* 
 PersonalHttpProxy 1.5
 Copyright (C) 2013-2015 Ingo Zenz

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

 Find the latest version at http://www.zenz-solutions.de/personalhttpproxy
 Contact:i.z@gmx.net 
 */

package com.bridge.filt3r.core.util;

import java.util.LinkedHashMap;
import java.util.Map;
 public class LRUCache<K, V> extends LinkedHashMap<K, V> {

	 private final int maxEntries;

	 public LRUCache(int maxEntries) {
		 super(maxEntries + 1, 0.75f, true); // accessOrder = true → echtes LRU
		 this.maxEntries = maxEntries;
	 }

	 @Override
	 protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
		 return size() > maxEntries;
	 }

	 @Override
	 public synchronized V get(Object key) {
		 return super.get(key);
	 }

	 @Override
	 public synchronized V put(K key, V value) {
		 return super.put(key, value);
	 }

	 @Override
	 public synchronized void clear() {
		 super.clear();
	 }

	 @Override
	 public synchronized int size() {
		 return super.size();
	 }

	 @Override
	 public synchronized V remove(Object key) {
		 return super.remove(key);
	 }
 }
