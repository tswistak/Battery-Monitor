#!/usr/bin/env ruby

require "fileutils"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
DRAWABLE_DIRS = [
  File.join(ROOT, "app/res/drawable"),
  File.join(ROOT, "app/res/drawable-hdpi"),
].freeze

FONT = "Helvetica-Narrow-Bold"
PERCENT_RANGE = 0..100
TEMP_RANGE = 0..150
DEGREE = "\u00B0"

SUFFIX_SPECS = {
  "%" => {
    suffix_scale: 0.68,
    gap_scale: 0.00,
    mode: :bottom,
  },
  DEGREE => {
    suffix_scale: 0.78,
    gap_scale: -0.10,
    rise_scale: 0.12,
    mode: :superscript,
  },
}.freeze

TEXT_VARIANTS = {
  number: {
    suffix: nil,
    range: 0..150,
    output_name: ->(prefix, value) { "#{prefix}#{format('%03d', value)}.png" },
    sample_number: "150",
  },
  percentage: {
    suffix: "%",
    range: 0..100,
    output_name: ->(prefix, value) { "#{prefix}_percentage#{format('%03d', value)}.png" },
    sample_number: "100",
  },
  temperature: {
    suffix: DEGREE,
    range: 0..150,
    output_name: ->(prefix, value) { "#{prefix}_temp#{format('%03d', value)}.png" },
    sample_number: "150",
  },
}.freeze

POINTSIZE_SAMPLE_VALUES = {
  number: {
    1 => 8,
    2 => 88,
    3 => 148,
  },
  percentage: {
    1 => 8,
    2 => 88,
    3 => 100,
  },
  temperature: {
    1 => 8,
    2 => 88,
    3 => 148,
  },
}.freeze

VARIANTS = {
  "n" => {
    sample: "n100.png",
    fill: "black",
    overlay_source: nil,
    y_shift_ratio: 0.00,
    height_pad: 2,
  },
  "plain" => {
    sample: "plain100.png",
    fill: "white",
    overlay_source: nil,
    y_shift_ratio: 0.00,
    height_pad: 2,
  },
  "charging" => {
    sample: "plain100.png",
    fill: "white",
    overlay_source: "charging",
    y_shift_ratio: -0.08,
    height_pad: 2,
  },
}.freeze

FALLBACK_SAMPLE_SIZES = {
  "app/res/drawable" => {
    "n" => [25, 25],
    "plain" => [25, 25],
    "charging" => [25, 25],
  },
  "app/res/drawable-hdpi" => {
    "n" => [38, 38],
    "plain" => [48, 48],
    "charging" => [48, 48],
  },
}.freeze

TEXT_TRIM_TARGETS = {
  "app/res/drawable" => {
    "n" => {
      number: [20, 16],
      percentage: [22, 16],
      temperature: [21, 17],
    },
    "plain" => {
      number: [20, 16],
      percentage: [22, 16],
      temperature: [21, 17],
    },
    "charging" => {
      number: [20, 16],
      percentage: [22, 16],
      temperature: [21, 17],
    },
  },
  "app/res/drawable-hdpi" => {
    "n" => {
      number: [31, 26],
      percentage: [34, 24],
      temperature: [33, 26],
    },
    "plain" => {
      number: [45, 34],
      percentage: [47, 31],
      temperature: [46, 33],
    },
    "charging" => {
      number: [45, 34],
      percentage: [47, 31],
      temperature: [46, 33],
    },
  },
}.freeze

POINTSIZE_SEARCH = {
  "app/res/drawable" => {
    "n" => (12..24),
    "plain" => (12..24),
    "charging" => (12..24),
  },
  "app/res/drawable-hdpi" => {
    "n" => (18..34),
    "plain" => (22..44),
    "charging" => (22..44),
  },
}.freeze

def run(*cmd)
  success = system(*cmd, out: File::NULL, err: File::NULL)
  raise "Command failed: #{cmd.join(' ')}" unless success
end

def command_output(*cmd)
  output = IO.popen(cmd, &:read)
  raise "Command failed: #{cmd.join(' ')}" unless $?.success?

  output.strip
end

def image_size(path)
  width, height = command_output("magick", "identify", "-format", "%w %h", path).split.map(&:to_i)
  [width, height]
end

def trimmed_size(path)
  width, height = command_output("magick", path, "-trim", "+repage", "-format", "%w %h", "info:").split.map(&:to_i)
  [width, height]
end

def trimmed_size_or_zero(path)
  output = IO.popen(["magick", path, "-trim", "+repage", "-format", "%w %h", "info:"], err: File::NULL, &:read).strip
  return [0, 0] if output.empty?

  output.split.map(&:to_i)
end

def sample_dimensions(base_dir, prefix, sample_name)
  sample_path = File.join(base_dir, sample_name)
  if File.exist?(sample_path)
    return image_size(sample_path)
  end

  relative_dir = base_dir.sub(ROOT + "/", "")
  FALLBACK_SAMPLE_SIZES.fetch(relative_dir).fetch(prefix)
end

def render_text_pair(out_path, number, suffix, fill, pointsize)
  if suffix.nil?
    run(
      "magick",
      "-background", "none",
      "-fill", fill,
      "-font", FONT,
      "-pointsize", pointsize.to_s,
      "label:#{number}",
      "-trim",
      "+repage",
      "PNG32:#{out_path}"
    )
    return
  end

  suffix_spec = SUFFIX_SPECS.fetch(suffix)
  Dir.mktmpdir("icon-text") do |dir|
    main_path = File.join(dir, "main.png")
    suffix_path = File.join(dir, "suffix.png")
    pair_path = File.join(dir, "pair.png")

    run(
      "magick",
      "-background", "none",
      "-fill", fill,
      "-font", FONT,
      "-pointsize", pointsize.to_s,
      "label:#{number}",
      "-trim",
      "+repage",
      "PNG32:#{main_path}"
    )
    run(
      "magick",
      "-background", "none",
      "-fill", fill,
      "-font", FONT,
      "-pointsize", [1, (pointsize * suffix_spec[:suffix_scale]).round].max.to_s,
      "label:#{suffix}",
      "-trim",
      "+repage",
      "PNG32:#{suffix_path}"
    )

    main_w, main_h = image_size(main_path)
    suffix_w, suffix_h = image_size(suffix_path)

    gap = (pointsize * suffix_spec[:gap_scale]).round

    suffix_x = main_w + gap
    main_x = 0
    if suffix_spec[:mode] == :bottom
      main_y = 0
      suffix_y = [0, main_h - suffix_h].max
    else
      rise = (pointsize * suffix_spec[:rise_scale]).round
      main_y = rise
      suffix_y = 0
    end

    width = [main_x + main_w, suffix_x + suffix_w].max
    height = [main_y + main_h, suffix_y + suffix_h].max

    run(
      "magick",
      "-size", "#{width}x#{height}",
      "xc:none",
      main_path,
      "-geometry", "+#{main_x}+#{main_y}",
      "-composite",
      suffix_path,
      "-geometry", "+#{suffix_x}+#{suffix_y}",
      "-composite",
      "PNG32:#{pair_path}"
    )
    run("magick", pair_path, "-trim", "+repage", "PNG32:#{out_path}")
  end
end

def fit_pointsize(target_width, target_height, fill, suffix, sample_text, candidate_range)
  Dir.mktmpdir("icon-fit") do |dir|
    probe = File.join(dir, "probe.png")
    best = candidate_range.min

    candidate_range.each do |candidate|
      render_text_pair(probe, sample_text, suffix, fill, candidate)
      width, height = image_size(probe)
      next unless width <= target_width && height <= target_height

      best = candidate
    end

    best
  end
end

def fit_pointsize_for_value(base_dir, prefix, variant_name, fill, value)
  relative_dir = base_dir.sub(ROOT + "/", "")
  target_width, target_height = TEXT_TRIM_TARGETS.fetch(relative_dir).fetch(prefix).fetch(variant_name)
  candidate_range = POINTSIZE_SEARCH.fetch(relative_dir).fetch(prefix)
  suffix = TEXT_VARIANTS.fetch(variant_name)[:suffix]
  fit_pointsize(target_width, target_height, fill, suffix, value.to_s, candidate_range)
end

def digit_bucket(value)
  return 3 if value >= 100
  return 2 if value >= 10

  1
end

def extract_overlay(base_dir, prefix)
  overlay_source = VARIANTS.fetch(prefix)[:overlay_source]
  return nil if overlay_source.nil?

  inputs = Dir.glob(File.join(base_dir, "#{overlay_source}[0-9][0-9][0-9].png")).sort.select do |path|
    File.basename(path)[overlay_source.length, 3].to_i <= 100
  end
  raise "No overlay source icons found for #{overlay_source} in #{base_dir}" if inputs.empty?

  Dir.mktmpdir("icon-overlay") do |dir|
    overlay = File.join(dir, "#{overlay_source}_overlay.png")
    run("magick", *inputs, "-evaluate-sequence", "min", "PNG32:#{overlay}")
    yield overlay
  end
end

def clear_family(base_dir, prefix)
  Dir.glob(File.join(base_dir, "#{prefix}[0-9][0-9][0-9].png")).each do |path|
    File.delete(path)
  end
  Dir.glob(File.join(base_dir, "#{prefix}_percentage[0-9][0-9][0-9].png")).each { |path| File.delete(path) }
  Dir.glob(File.join(base_dir, "#{prefix}_temp[0-9][0-9][0-9].png")).each { |path| File.delete(path) }
end

def compose_icon(base_dir, prefix, value, variant_name, pointsize, overlay_path, width, height, y_shift)
  spec = VARIANTS.fetch(prefix)
  text_variant = TEXT_VARIANTS.fetch(variant_name)

  Dir.mktmpdir("icon-compose") do |dir|
    text_path = File.join(dir, "text.png")
    out_path = File.join(dir, "out.png")

    render_text_pair(text_path, value, text_variant[:suffix], spec[:fill], pointsize)
    text_w, text_h = image_size(text_path)

    x = (width - text_w) / 2
    y = (height - text_h) / 2 + y_shift

    cmd = ["magick", "-size", "#{width}x#{height}", "xc:none"]
    unless overlay_path.nil?
      cmd.concat([overlay_path, "-composite"])
    end
    cmd.concat([
      text_path,
      "-geometry", "#{x >= 0 ? '+' : ''}#{x}#{y >= 0 ? '+' : ''}#{y}",
      "-composite",
      "PNG32:#{out_path}"
    ])
    run(*cmd)

    final_name = text_variant[:output_name].call(prefix, value)
    FileUtils.cp(out_path, File.join(base_dir, final_name))
  end
end

def generate_family(base_dir, prefix)
  spec = VARIANTS.fetch(prefix)
  width, height = sample_dimensions(base_dir, prefix, spec[:sample])
  y_shift = (height * spec[:y_shift_ratio]).round

  pointsize_cache = Hash.new do |cache, key|
    variant_name, bucket = key
    sample_value = POINTSIZE_SAMPLE_VALUES.fetch(variant_name).fetch(bucket)
    cache[key] = fit_pointsize_for_value(base_dir, prefix, variant_name, spec[:fill], sample_value)
  end

  extractor = proc do |overlay|
    TEXT_VARIANTS.each do |variant_name, text_variant|
      text_variant[:range].each do |value|
        compose_icon(
          base_dir,
          prefix,
          value,
          variant_name,
          pointsize_cache[[variant_name, digit_bucket(value)]],
          overlay,
          width,
          height,
          y_shift
        )
      end
    end
  end

  if spec[:overlay_source].nil?
    clear_family(base_dir, prefix)
    extractor.call(nil)
  else
    extract_overlay(base_dir, prefix) do |overlay|
      clear_family(base_dir, prefix)
      extractor.call(overlay)
    end
  end

  puts(
    "#{base_dir.sub(ROOT + '/', '')} #{prefix}: #{width}x#{height}, " \
    "1-digit=#{pointsize_cache[[:number, 1]]}, " \
    "2-digit=#{pointsize_cache[[:number, 2]]}, " \
    "3-digit=#{pointsize_cache[[:number, 3]]}, " \
    "2-digit-percent=#{pointsize_cache[[:percentage, 2]]}, " \
    "2-digit-degree=#{pointsize_cache[[:temperature, 2]]}"
  )
end

DRAWABLE_DIRS.each do |base_dir|
  VARIANTS.keys.each do |prefix|
    generate_family(base_dir, prefix)
  end
end
