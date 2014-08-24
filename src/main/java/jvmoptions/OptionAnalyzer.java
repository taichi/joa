/* 
 * Copyright 2014 SATO taichi
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jvmoptions;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import rx.Observable;
import rx.observables.StringObservable;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author taichi
 */
public class OptionAnalyzer {

	public static void main(String[] args) throws Exception {
		File f = new File("result");
		if (f.exists() == false && f.mkdirs() == false) {
			System.exit(1);
		}
		Path json = toJson("java6", "java7", "java8");
		toCSV(json);
	}

	static final String TS = new SimpleDateFormat("YYYYMMdd_HHmmss")
			.format(new Date());

	static String filename(String extension) {
		return String.format("compare_%s.%s", TS, extension);
	}

	static Path toJson(String java6, String java7, String java8)
			throws Exception {
		Map<String, Map<String, String>> map6 = makeMap(java6);
		Map<String, Map<String, String>> map7 = makeMap(java7);
		Map<String, Map<String, String>> map8 = makeMap(java8);

		Path output = Paths.get("result", filename("json"));

		JsonFactory factory = new JsonFactory();
		JsonGenerator jg = factory.createGenerator(output.toFile(),
				JsonEncoding.UTF8).useDefaultPrettyPrinter();

		jg.writeStartObject();
		Stream.of(map6, map7, map8)
				.map(Map::keySet)
				.flatMap(Collection::stream)
				.sorted()
				.distinct()
				.forEach(
						k -> {
							try {
								jg.writeFieldName(k);
								jg.writeStartObject();

								Map<String, String> base = pick(k, map8, map7,
										map6);
								jg.writeStringField("kind", base.get("kind"));
								jg.writeStringField("type", base.get("type"));
								jg.writeStringField("description",
										base.get("description"));
								jg.writeStringField("file", base.get("file"));

								write(jg, "java6", map6.get(k));
								write(jg, "java7", map7.get(k));
								write(jg, "java8", map8.get(k));

								jg.writeEndObject();
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
		jg.writeEndObject();

		jg.close();

		return output;
	}

	static void write(JsonGenerator jg, String version, Map<String, String> map)
			throws IOException {
		boolean exists = map != null;
		jg.writeStringField(version, exists ? "o" : "x");
		String def = "";
		if (exists) {
			def = map.get("default");
		}
		jg.writeStringField(version + ".default", def);
	}

	@SafeVarargs
	static Map<String, String> pick(String key,
			Map<String, Map<String, String>>... maps) {
		for (Map<String, Map<String, String>> m : maps) {
			if (m.containsKey(key)) {
				return m.get(key);
			}
		}
		throw new IllegalStateException("missing option " + key);
	}

	static Map<String, Map<String, String>> makeMap(String root)
			throws Exception {
		Path start = FileSystems.getDefault().getPath(root);
		File dir = start.toFile();
		if (dir.exists() == false || dir.isDirectory() == false) {
			System.err.printf("%s doesn't exists or doesn't dir %n", root);
			System.err.println("run gradle task below");
			System.err.println("gradlew getSrcs");
			System.exit(1);
		}

		return Files
				.walk(start)
				.map(Path::toFile)
				.filter(File::isFile)
				.filter(f -> f.getName().endsWith(".hpp"))
				.map(File::toPath)
				.parallel()
				.filter(OptionAnalyzer::contains)
				.map(OptionAnalyzer::parse)
				.map(Map::entrySet)
				.flatMap(Collection::stream)
				.collect(
						Collectors.toMap(Map.Entry::getKey,
								Map.Entry::getValue, OptionAnalyzer::merge));
	}

	static Map<String, String> merge(Map<String, String> l,
			Map<String, String> r) {
		Map<String, String> m = new HashMap<>(l);
		m.put("file", String.join("|", l.get("file"), r.get("file")));
		return m;
	}

	static boolean contains(Path path) {
		try {
			byte[] bytes = Files.readAllBytes(path);
			String s = new String(bytes);
			return s.contains("_FLAGS(develop");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static Map<String, Map<String, String>> parse(Path hpp) {
		System.out.printf("process %s %n", hpp);
		String file = hpp.subpath(4, hpp.getNameCount()).toString()
				.replace(File.separatorChar, '/');
		try {
			String all = preprocess(hpp);

			String categories = "(?<kind>develop|develop_pd|product|product_pd|diagnostic|experimental|notproduct|manageable|product_rw|lp64_product)";

			// detect Regex bugs.
			List<String> names = parseNames(all, categories);
			Set<String> nameSet = new HashSet<>();

			Pattern descPtn = Pattern.compile("\"((\\\\\"|[^\"])+)\"");
			Pattern pattern = Pattern
					.compile(categories
							+ "\\((?<type>\\w+?),[ ]*(?<name>\\w+)[ ]*(,[ ]*(?<default>[\\w ()\\-+/*.\"]+))?,[ ]*(?<desc>("
							+ descPtn.pattern() + "[ ]*)+)\\)");

			Map<String, Map<String, String>> result = new HashMap<>();

			int times = 0;
			for (Matcher matcher = pattern.matcher(all); matcher.find(); times++) {
				String name = matcher.group("name");
				verify(names, nameSet, times, name);

				String def = Objects.toString(matcher.group("default"), "");

				String d = matcher.group("desc");
				StringBuilder desc = new StringBuilder();
				for (Matcher m = descPtn.matcher(d); m.find();) {
					desc.append(m.group(1).replaceAll("\\\\", ""));
				}

				Map<String, String> m = new HashMap<>();
				m.put("kind", matcher.group("kind"));
				m.put("type", matcher.group("type"));
				m.put("default", def);
				m.put("description", desc.toString());
				m.put("file", file);
				result.put(name, m);
			}
			System.out.printf("        %s contains %d options%n", hpp, times);

			return result;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static final Pattern BEGIN = Pattern.compile("^#define \\w+_FLAGS.*");

	static String preprocess(Path hpp) throws IOException {
		Observable<String> globals_hpp = StringObservable.from(Files
				.newBufferedReader(hpp));
		Observable<String> o = StringObservable.split(globals_hpp, "\r?\n")
				.skipWhile(line -> BEGIN.matcher(line).matches() == false)
				.skip(2).map(line -> line.replaceAll("\\\\$", ""))
				.map(line -> line.replaceAll("/\\*.*?\\*/", ""))
				.filter(line -> line.matches("^[ ]+$") == false);
		String all = StringObservable.join(o, "").toBlockingObservable()
				.first();
		return all.replaceAll("EMBEDDED_ONLY\\(\\w*\\(.*?\\)\\)", "");
	}

	static List<String> parseNames(String all, String categories) {
		List<String> names = new ArrayList<>();
		Matcher m = Pattern.compile(categories + "\\(\\w+,[ ]*(\\w+)").matcher(
				all);
		while (m.find()) {
			names.add(m.group(2));
		}
		return names;
	}

	static void verify(List<String> names, Set<String> nameSet, int times,
			String name) {
		if (name.equals(names.get(times)) == false) {
			throw new IllegalStateException(String.format(
					"shoudbe>%s matched>%s%n", names.get(times), name));
		}
		if (nameSet.add(name) == false) {
			String fmt = String.format("%s is already exists", name);
			System.err.println(fmt);
		}
	}

	static void toCSV(Path json) throws IOException {
		Map<String, Map<String, String>> map = readJson(json);
		try (BufferedWriter w = Files.newBufferedWriter(Paths.get("result",
				filename("csv")))) {
			PrintWriter pw = new PrintWriter(w);
			pw.println("name,description,type,6,7,8,6.default,7.default,8.default,kind,file");
			map.keySet()
					.stream()
					.sorted()
					.map(k -> {
						Map<String, String> m = map.get(k);
						return String.format("%s, \" %s\", %s", k, m
								.get("description"), String.join(", ",
								m.get("type"), m.get("java6"), m.get("java7"),
								m.get("java8"), m.get("java6.default"),
								m.get("java7.default"), m.get("java8.default"),
								m.get("kind"), m.get("file")));
					}).forEach(pw::println);
		}
	}

	static Map<String, Map<String, String>> readJson(Path json)
			throws IOException {
		ObjectMapper om = new ObjectMapper();
		TypeReference<Map<String, Map<String, String>>> ref = new TypeReference<Map<String, Map<String, String>>>() {
		};

		Map<String, Map<String, String>> map = om.readValue(json.toFile(), ref);
		System.out
				.printf("%s contains %d options%n", json, map.keySet().size());
		return map;
	}
}
