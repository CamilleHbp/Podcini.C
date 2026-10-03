#pragma once
#include <array>
#include <memory>
#include <stdexcept>
#include <string>
#include <fileref.h>
#include <tpropertymap.h>
#include <mpegfile.h>
#include <id3v2framefactory.h>
#include <flacfile.h>
#include <vorbisfile.h>
#include <opusfile.h>
#include <mp4file.h>

namespace PodciniTags {
using Fields = std::array<TagLib::StringList, 4>;
inline constexpr const char *keys[] = {"GENRE", "MOOD", "TAGS", "PODCINI_PATHS"};

inline void validate(const TagLib::FileRef &file) {
    if(file.isNull() || !file.file()->isValid()) throw std::runtime_error("Invalid audio file");
    if(auto mp4 = dynamic_cast<TagLib::MP4::File *>(file.file())) {
        if(!mp4->audioProperties() || mp4->audioProperties()->isEncrypted())
            throw std::runtime_error("Protected or invalid MP4 audio");
    } else if(!dynamic_cast<TagLib::MPEG::File *>(file.file()) &&
              !dynamic_cast<TagLib::FLAC::File *>(file.file()) &&
              !dynamic_cast<TagLib::Ogg::Vorbis::File *>(file.file()) &&
              !dynamic_cast<TagLib::Ogg::Opus::File *>(file.file()))
        throw std::runtime_error("Unsupported audio container");
}

inline Fields read(const std::string &path) {
    TagLib::FileRef file(path.c_str(), true, TagLib::AudioProperties::Fast);
    validate(file);
    const auto properties = file.file()->properties();
    Fields result;
    for(size_t i = 0; i < result.size(); ++i) result[i] = properties.value(keys[i]);
    return result;
}

inline void write(const std::string &path, const Fields &fields) {
    TagLib::ID3v2::FrameFactory::instance()->setDefaultTextEncoding(TagLib::String::UTF8);
    TagLib::FileRef file(path.c_str(), true, TagLib::AudioProperties::Fast);
    validate(file);
    // Keep all unrelated properties and opaque frames in the original TagLib object.
    auto properties = file.file()->properties();
    for(size_t i = 0; i < fields.size(); ++i) {
        if(fields[i].isEmpty()) properties.erase(keys[i]);
        else properties.replace(keys[i], fields[i]);
    }
    const auto rejected = file.file()->setProperties(properties);
    for(const auto *key : keys)
        if(rejected.contains(key)) throw std::runtime_error("Container rejected a tag field");
    auto mp3 = dynamic_cast<TagLib::MPEG::File *>(file.file());
    const bool saved = mp3 ? mp3->save(TagLib::MPEG::File::AllTags, TagLib::File::StripNone,
                                     TagLib::ID3v2::v4, TagLib::File::DoNotDuplicate)
                           : file.save();
    if(!saved) throw std::runtime_error("Could not save metadata");
}
}
