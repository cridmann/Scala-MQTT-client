/*
 * Copyright 2014 Frédéric Cabestre
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.impl.frames

import java.io.File

import net.sigusr.mqtt.SpecUtils._
import net.sigusr.mqtt.api._
import org.specs2.mutable._
import scodec.bits._
import scodec.{SizeBound, DecodeResult, Codec, Err}

import scala.util.Random

object CodecSpec extends Specification {

  "A remaining length codec" should {

    "Provide its size bounds" in {
      remainingLengthCodec.sizeBound should_=== SizeBound.bounded(8, 32)
    }

    "Perform encoding of valid inputs" in {
      remainingLengthCodec.encode(0) should succeedWith(hex"00".bits)
      remainingLengthCodec.encode(127) should succeedWith(hex"7f".bits)
      remainingLengthCodec.encode(128) should succeedWith(hex"8001".bits)
      remainingLengthCodec.encode(16383) should succeedWith(hex"ff7f".bits)
      remainingLengthCodec.encode(16384) should succeedWith(hex"808001".bits)
      remainingLengthCodec.encode(2097151) should succeedWith(hex"ffff7f".bits)
      remainingLengthCodec.encode(2097152) should succeedWith(hex"80808001".bits)
      remainingLengthCodec.encode(268435455) should succeedWith(hex"ffffff7f".bits)
    }

    "Fail to encode certain input values" in {
      remainingLengthCodec.encode(-1) should failWith(Err(s"The remaining length must be in the range [0..268435455], -1 is not valid"))
      remainingLengthCodec.encode(268435455 + 1) should failWith(Err(s"The remaining length must be in the range [0..268435455], 268435456 is not valid"))
    }

    "Perform decoding of valid inputs" in {
      remainingLengthCodec.decode(hex"00".bits) should succeedWith(DecodeResult(0, BitVector.empty))
      remainingLengthCodec.decode(hex"7f".bits) should succeedWith(DecodeResult(127, BitVector.empty))
      remainingLengthCodec.decode(hex"8001".bits) should succeedWith(DecodeResult(128, BitVector.empty))
      remainingLengthCodec.decode(hex"ff7f".bits) should succeedWith(DecodeResult(16383, BitVector.empty))
      remainingLengthCodec.decode(hex"808001".bits) should succeedWith(DecodeResult(16384, BitVector.empty))
      remainingLengthCodec.decode(hex"ffff7f".bits) should succeedWith(DecodeResult(2097151, BitVector.empty))
      remainingLengthCodec.decode(hex"80808001".bits) should succeedWith(DecodeResult(2097152, BitVector.empty))
      remainingLengthCodec.decode(hex"ffffff7f".bits) should succeedWith(DecodeResult(268435455, BitVector.empty))
    }

    "Fail to decode certain input values" in {
      remainingLengthCodec.decode(hex"808080807f".bits) should failWith(Err("The remaining length must be 4 bytes long at most"))
    }
  }

  "A header codec" should {
    "Perform encoding of valid input" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = true)
      Codec.encode(header) should succeedWith(bin"0011")
    }

    "Perform decoding of valid inputs" in {
      val header = Header(dup = true, ExactlyOnce.enum, retain = false)
      Codec[Header].decode(bin"1100110011") should succeedWith(DecodeResult(header, bin"110011"))
    }
  }

  "A connect variable header codec" should {
    "Perform encoding of valid inputs" in {

      import net.sigusr.mqtt.impl.frames.ConnectVariableHeader._

      val connectVariableHeader = ConnectVariableHeader(cleanSession = true, willFlag = true, willQoS = AtMostOnce.enum, willRetain = false, passwordFlag = true, userNameFlag = true, keepAliveTimer = 1024)
      val res = connectVariableHeaderFixedBytes ++ bin"110001100000010000000000"
      Codec.encode(connectVariableHeader) should succeedWith(res)
    }

    "Perform decoding of valid inputs" in {

      import net.sigusr.mqtt.impl.frames.ConnectVariableHeader._

      val res = ConnectVariableHeader(cleanSession = false, willFlag = false, willQoS = AtLeastOnce.enum, willRetain = true, passwordFlag = false, userNameFlag = false, keepAliveTimer = 12683)
      Codec[ConnectVariableHeader].decode(connectVariableHeaderFixedBytes ++ bin"001010000011000110001011101010") should succeedWith(DecodeResult(res, bin"101010"))
    }
  }

  "A connect message codec should" should {
    "[0] Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val connectVariableHeader = ConnectVariableHeader(userNameFlag = true, passwordFlag = true, willRetain = true, AtLeastOnce.enum, willFlag = true, cleanSession = true, 15)
      val connectMessage = ConnectFrame(header, connectVariableHeader, "clientId", Some("Topic"), Some("Message"), Some("User"), Some("Password"))

      val valid = Codec[Frame].encode(connectMessage).require
      Codec[Frame].decode(valid) should succeedWith(DecodeResult(connectMessage, bin""))
    }

    "[1] Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val connectVariableHeader = ConnectVariableHeader(userNameFlag = true, passwordFlag = false, willRetain = true, AtLeastOnce.enum, willFlag = false, cleanSession = true, 15)
      val connectMessage = ConnectFrame(header, connectVariableHeader, "clientId", None, None, Some("User"), None)

      Codec[Frame].decode(Codec[Frame].encode(connectMessage).require) should succeedWith(DecodeResult(connectMessage, bin""))
    }

    "[2] Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val connectVariableHeader = ConnectVariableHeader(userNameFlag = false, passwordFlag = false, willRetain = true, ExactlyOnce.enum, willFlag = false, cleanSession = false, 128)
      val connectMessage = ConnectFrame(header, connectVariableHeader, "clientId", None, None, None, None)

      Codec[Frame].decode(Codec[Frame].encode(connectMessage).require) should succeedWith(DecodeResult(connectMessage, bin""))
    }

    "Perform encoding and match a captured value" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val connectVariableHeader = ConnectVariableHeader(userNameFlag = false, passwordFlag = false, willRetain = true, AtLeastOnce.enum, willFlag = true, cleanSession = false, 60)
      val connectMessage = ConnectFrame(header, connectVariableHeader, "test", Some("test/topic"), Some("test death"), None, None)

      val capture = BitVector(0x10, 0x2a, 0x00, 0x06, 0x4d, 0x51, 0x49, 0x73, 0x64, 0x70, 0x03, 0x2c, 0x00, 0x3c, 0x00, 0x04, 0x74, 0x65, 0x73, 0x74, 0x00, 0x0a, 0x74, 0x65, 0x73, 0x74, 0x2f, 0x74, 0x6f, 0x70, 0x69, 0x63, 0x00, 0x0a, 0x74, 0x65, 0x73, 0x74, 0x20, 0x64, 0x65, 0x61, 0x74, 0x68)
      Codec[Frame].encode(connectMessage) should succeedWith(capture)
    }
  }

  "A connack message codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val connackFrame = ConnackFrame(header, 0)

      Codec[Frame].decode(Codec[Frame].encode(connackFrame).require) should succeedWith(DecodeResult(connackFrame, bin""))
    }

    "Perform decoding of captured values" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val connackFrame = ConnackFrame(header, 0)

      Codec[Frame].decode(BitVector(0x20, 0x02, 0x00, 0x00)) should succeedWith(DecodeResult(connackFrame, bin""))
    }
  }

  "A topics codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      import net.sigusr.mqtt.impl.frames.SubscribeFrame._
      val topics = Vector(("topic0", AtMostOnce.enum), ("topic1", AtLeastOnce.enum), ("topic2", ExactlyOnce.enum))
      Codec[Vector[(String, Int)]].decode(Codec[Vector[(String, Int)]].encode(topics).require) should succeedWith(DecodeResult(topics, bin""))
    }
  }

  "A subscribe codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val topics = Vector(("topic0", AtMostOnce.enum), ("topic1", AtLeastOnce.enum), ("topic2", ExactlyOnce.enum))
      val subscribeFrame = SubscribeFrame(header, 3, topics)
      Codec[Frame].decode(Codec[Frame].encode(subscribeFrame).require) should succeedWith(DecodeResult(subscribeFrame, bin""))
    }

    "Perform encoding and match a captured value" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val topics = Vector(("topic", AtLeastOnce.enum))
      val subscribeFrame = SubscribeFrame(header, 1, topics)
      val capture = BitVector(0x82, 0x0a, 0x00, 0x01, 0x00, 0x05, 0x74, 0x6f, 0x70, 0x69, 0x63, 0x01)
      Codec[Frame].encode(subscribeFrame) should succeedWith(capture)
    }
  }

  "A suback codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val qos = Vector(AtMostOnce.enum, AtLeastOnce.enum, ExactlyOnce.enum)
      val subackFrame = SubackFrame(header, 3, qos)
      Codec[Frame].decode(Codec[Frame].encode(subackFrame).require) should succeedWith(DecodeResult(subackFrame, bin""))
    }
  }

  "An unsubscribe codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val topics = Vector("topic0", "topic1")
      val unsubscribeFrame = UnsubscribeFrame(header, Random.nextInt(65536), topics)
      Codec[Frame].decode(Codec[Frame].encode(unsubscribeFrame).require) should succeedWith(DecodeResult(unsubscribeFrame, bin""))
    }
  }

  "An unsuback codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val unsubackFrame = UnsubackFrame(header, Random.nextInt(65536))
      Codec[Frame].decode(Codec[Frame].encode(unsubackFrame).require) should succeedWith(DecodeResult(unsubackFrame, bin""))
    }
  }

  "A disconnect message codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val disconnectFrame = DisconnectFrame(header)

      Codec[Frame].decode(Codec[Frame].encode(disconnectFrame).require) should succeedWith(DecodeResult(disconnectFrame, bin""))
    }
  }

  "A ping request message codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val pingReqFrame = PingReqFrame(header)

      Codec[Frame].decode(Codec[Frame].encode(pingReqFrame).require) should succeedWith(DecodeResult(pingReqFrame, bin""))
    }

    "Perform encoding and match a captured value" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val connectMessage = PingReqFrame(header)

      val capture = BitVector(0xc0, 0x00)
      Codec[Frame].encode(connectMessage) should succeedWith(capture)
    }
  }

  "A ping response message codec" should {
    "Perform round trip encoding/decoding of a valid input" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val pingRespFrame = PingRespFrame(header)

      Codec[Frame].decode(Codec[Frame].encode(pingRespFrame).require) should succeedWith(DecodeResult(pingRespFrame, bin""))
    }

    "Perform decoding of captured values" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val pingRespFrame = PingRespFrame(header)

      Codec[Frame].decode(BitVector(0xd0, 0x00)) should succeedWith(DecodeResult(pingRespFrame, bin""))
    }
  }

  "A publish message codec" should {
    "Perform round trip encoding/decoding of a valid input with a QoS greater than 0" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val topic = "a/b"
      val publishFrame = PublishFrame(header, topic, 10, ByteVector("Hello world".getBytes))

      Codec[Frame].decode(Codec[Frame].encode(publishFrame).require) should succeedWith(DecodeResult(publishFrame, bin""))
    }

    "Perform a round trip with a large message" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val topic = "a/b"
      val macbeth =
        """
          |The Tragedy of Macbeth
          |ACT I
          |
          |SCENE I. A desert place.
          |
          |Thunder and lightning. Enter three Witches
          |First Witch
          |When shall we three meet again
          |In thunder, lightning, or in rain?
          |Second Witch
          |When the hurlyburly's done,
          |When the battle's lost and won.
          |Third Witch
          |That will be ere the set of sun.
          |First Witch
          |Where the place?
          |Second Witch
          |Upon the heath.
          |Third Witch
          |There to meet with Macbeth.
          |First Witch
          |I come, Graymalkin!
          |Second Witch
          |Paddock calls.
          |Third Witch
          |Anon.
          |ALL
          |Fair is foul, and foul is fair:
          |Hover through the fog and filthy air.
          |Exeunt
          |
          |SCENE II. A camp near Forres.
          |
          |Alarum within. Enter DUNCAN, MALCOLM, DONALBAIN, LENNOX, with Attendants, meeting a bleeding Sergeant
          |DUNCAN
          |What bloody man is that? He can report,
          |As seemeth by his plight, of the revolt
          |The newest state.
          |MALCOLM
          |This is the sergeant
          |Who like a good and hardy soldier fought
          |'Gainst my captivity. Hail, brave friend!
          |Say to the king the knowledge of the broil
          |As thou didst leave it.
          |Sergeant
          |Doubtful it stood;
          |As two spent swimmers, that do cling together
          |And choke their art. The merciless Macdonwald--
          |Worthy to be a rebel, for to that
          |The multiplying villanies of nature
          |Do swarm upon him--from the western isles
          |Of kerns and gallowglasses is supplied;
          |And fortune, on his damned quarrel smiling,
          |Show'd like a rebel's whore: but all's too weak:
          |For brave Macbeth--well he deserves that name--
          |Disdaining fortune, with his brandish'd steel,
          |Which smoked with bloody execution,
          |Like valour's minion carved out his passage
          |Till he faced the slave;
          |Which ne'er shook hands, nor bade farewell to him,
          |Till he unseam'd him from the nave to the chaps,
          |And fix'd his head upon our battlements.
          |DUNCAN
          |O valiant cousin! worthy gentleman!
          |Sergeant
          |As whence the sun 'gins his reflection
          |Shipwrecking storms and direful thunders break,
          |So from that spring whence comfort seem'd to come
          |Discomfort swells. Mark, king of Scotland, mark:
          |No sooner justice had with valour arm'd
          |Compell'd these skipping kerns to trust their heels,
          |But the Norweyan lord surveying vantage,
          |With furbish'd arms and new supplies of men
          |Began a fresh assault.
          |DUNCAN
          |Dismay'd not this
          |Our captains, Macbeth and Banquo?
          |Sergeant
          |Yes;
          |As sparrows eagles, or the hare the lion.
          |If I say sooth, I must report they were
          |As cannons overcharged with double cracks, so they
          |Doubly redoubled strokes upon the foe:
          |Except they meant to bathe in reeking wounds,
          |Or memorise another Golgotha,
          |I cannot tell.
          |But I am faint, my gashes cry for help.
          |DUNCAN
          |So well thy words become thee as thy wounds;
          |They smack of honour both. Go get him surgeons.
          |Exit Sergeant, attended
          |
          |Who comes here?
          |Enter ROSS
          |
          |MALCOLM
          |The worthy thane of Ross.
          |LENNOX
          |What a haste looks through his eyes! So should he look
          |That seems to speak things strange.
          |ROSS
          |God save the king!
          |DUNCAN
          |Whence camest thou, worthy thane?
          |ROSS
          |From Fife, great king;
          |Where the Norweyan banners flout the sky
          |And fan our people cold. Norway himself,
          |With terrible numbers,
          |Assisted by that most disloyal traitor
          |The thane of Cawdor, began a dismal conflict;
          |Till that Bellona's bridegroom, lapp'd in proof,
          |Confronted him with self-comparisons,
          |Point against point rebellious, arm 'gainst arm.
          |Curbing his lavish spirit: and, to conclude,
          |The victory fell on us.
          |DUNCAN
          |Great happiness!
          |ROSS
          |That now
          |Sweno, the Norways' king, craves composition:
          |Nor would we deign him burial of his men
          |Till he disbursed at Saint Colme's inch
          |Ten thousand dollars to our general use.
          |DUNCAN
          |No more that thane of Cawdor shall deceive
          |Our bosom interest: go pronounce his present death,
          |And with his former title greet Macbeth.
          |ROSS
          |I'll see it done.
          |DUNCAN
          |What he hath lost noble Macbeth hath won.
          |Exeunt
          |
          |SCENE III. A heath near Forres.
          |
          |Thunder. Enter the three Witches
          |First Witch
          |Where hast thou been, sister?
          |Second Witch
          |Killing swine.
          |Third Witch
          |Sister, where thou?
          |First Witch
          |A sailor's wife had chestnuts in her lap,
          |And munch'd, and munch'd, and munch'd:--
          |'Give me,' quoth I:
          |'Aroint thee, witch!' the rump-fed ronyon cries.
          |Her husband's to Aleppo gone, master o' the Tiger:
          |But in a sieve I'll thither sail,
          |And, like a rat without a tail,
          |I'll do, I'll do, and I'll do.
          |Second Witch
          |I'll give thee a wind.
          |First Witch
          |Thou'rt kind.
          |Third Witch
          |And I another.
          |First Witch
          |I myself have all the other,
          |And the very ports they blow,
          |All the quarters that they know
          |I' the shipman's card.
          |I will drain him dry as hay:
          |Sleep shall neither night nor day
          |Hang upon his pent-house lid;
          |He shall live a man forbid:
          |Weary se'nnights nine times nine
          |Shall he dwindle, peak and pine:
          |Though his bark cannot be lost,
          |Yet it shall be tempest-tost.
          |Look what I have.
          |Second Witch
          |Show me, show me.
          |First Witch
          |Here I have a pilot's thumb,
          |Wreck'd as homeward he did come.
          |Drum within
          |
          |Third Witch
          |A drum, a drum!
          |Macbeth doth come.
          |ALL
          |The weird sisters, hand in hand,
          |Posters of the sea and land,
          |Thus do go about, about:
          |Thrice to thine and thrice to mine
          |And thrice again, to make up nine.
          |Peace! the charm's wound up.
          |Enter MACBETH and BANQUO
          |
          |MACBETH
          |So foul and fair a day I have not seen.
          |BANQUO
          |How far is't call'd to Forres? What are these
          |So wither'd and so wild in their attire,
          |That look not like the inhabitants o' the earth,
          |And yet are on't? Live you? or are you aught
          |That man may question? You seem to understand me,
          |By each at once her chappy finger laying
          |Upon her skinny lips: you should be women,
          |And yet your beards forbid me to interpret
          |That you are so.
          |MACBETH
          |Speak, if you can: what are you?
          |First Witch
          |All hail, Macbeth! hail to thee, thane of Glamis!
          |Second Witch
          |All hail, Macbeth, hail to thee, thane of Cawdor!
          |Third Witch
          |All hail, Macbeth, thou shalt be king hereafter!
          |BANQUO
          |Good sir, why do you start; and seem to fear
          |Things that do sound so fair? I' the name of truth,
          |Are ye fantastical, or that indeed
          |Which outwardly ye show? My noble partner
          |You greet with present grace and great prediction
          |Of noble having and of royal hope,
          |That he seems rapt withal: to me you speak not.
          |If you can look into the seeds of time,
          |And say which grain will grow and which will not,
          |Speak then to me, who neither beg nor fear
          |Your favours nor your hate.
          |First Witch
          |Hail!
          |Second Witch
          |Hail!
          |Third Witch
          |Hail!
          |First Witch
          |Lesser than Macbeth, and greater.
          |Second Witch
          |Not so happy, yet much happier.
          |Third Witch
          |Thou shalt get kings, though thou be none:
          |So all hail, Macbeth and Banquo!
          |First Witch
          |Banquo and Macbeth, all hail!
          |MACBETH
          |Stay, you imperfect speakers, tell me more:
          |By Sinel's death I know I am thane of Glamis;
          |But how of Cawdor? the thane of Cawdor lives,
          |A prosperous gentleman; and to be king
          |Stands not within the prospect of belief,
          |No more than to be Cawdor. Say from whence
          |You owe this strange intelligence? or why
          |Upon this blasted heath you stop our way
          |With such prophetic greeting? Speak, I charge you.
          |Witches vanish
          |
          |BANQUO
          |The earth hath bubbles, as the water has,
          |And these are of them. Whither are they vanish'd?
          |MACBETH
          |Into the air; and what seem'd corporal melted
          |As breath into the wind. Would they had stay'd!
          |BANQUO
          |Were such things here as we do speak about?
          |Or have we eaten on the insane root
          |That takes the reason prisoner?
          |MACBETH
          |Your children shall be kings.
          |BANQUO
          |You shall be king.
          |MACBETH
          |And thane of Cawdor too: went it not so?
          |BANQUO
          |To the selfsame tune and words. Who's here?
          |Enter ROSS and ANGUS
          |
          |ROSS
          |The king hath happily received, Macbeth,
          |The news of thy success; and when he reads
          |Thy personal venture in the rebels' fight,
          |His wonders and his praises do contend
          |Which should be thine or his: silenced with that,
          |In viewing o'er the rest o' the selfsame day,
          |He finds thee in the stout Norweyan ranks,
          |Nothing afeard of what thyself didst make,
          |Strange images of death. As thick as hail
          |Came post with post; and every one did bear
          |Thy praises in his kingdom's great defence,
          |And pour'd them down before him.
          |ANGUS
          |We are sent
          |To give thee from our royal master thanks;
          |Only to herald thee into his sight,
          |Not pay thee.
          |ROSS
          |And, for an earnest of a greater honour,
          |He bade me, from him, call thee thane of Cawdor:
          |In which addition, hail, most worthy thane!
          |For it is thine.
          |BANQUO
          |What, can the devil speak true?
          |MACBETH
          |The thane of Cawdor lives: why do you dress me
          |In borrow'd robes?
          |ANGUS
          |Who was the thane lives yet;
          |But under heavy judgment bears that life
          |Which he deserves to lose. Whether he was combined
          |With those of Norway, or did line the rebel
          |With hidden help and vantage, or that with both
          |He labour'd in his country's wreck, I know not;
          |But treasons capital, confess'd and proved,
          |Have overthrown him.
          |MACBETH
          |[Aside] Glamis, and thane of Cawdor!
          |The greatest is behind.
          |To ROSS and ANGUS
          |
          |Thanks for your pains.
          |To BANQUO
          |
          |Do you not hope your children shall be kings,
          |When those that gave the thane of Cawdor to me
          |Promised no less to them?
          |BANQUO
          |That trusted home
          |Might yet enkindle you unto the crown,
          |Besides the thane of Cawdor. But 'tis strange:
          |And oftentimes, to win us to our harm,
          |The instruments of darkness tell us truths,
          |Win us with honest trifles, to betray's
          |In deepest consequence.
          |Cousins, a word, I pray you.
          |MACBETH
          |[Aside]	Two truths are told,
          |As happy prologues to the swelling act
          |Of the imperial theme.--I thank you, gentlemen.
          |Aside
          |
          |Cannot be ill, cannot be good: if ill,
          |Why hath it given me earnest of success,
          |Commencing in a truth? I am thane of Cawdor:
          |If good, why do I yield to that suggestion
          |Whose horrid image doth unfix my hair
          |And make my seated heart knock at my ribs,
          |Against the use of nature? Present fears
          |Are less than horrible imaginings:
          |My thought, whose murder yet is but fantastical,
          |Shakes so my single state of man that function
          |Is smother'd in surmise, and nothing is
          |But what is not.
          |BANQUO
          |Look, how our partner's rapt.
          |MACBETH
          |[Aside] If chance will have me king, why, chance may crown me,
          |Without my stir.
          |BANQUO
          |New horrors come upon him,
          |Like our strange garments, cleave not to their mould
          |But with the aid of use.
          |MACBETH
          |[Aside] Come what come may,
          |Time and the hour runs through the roughest day.
          |BANQUO
          |Worthy Macbeth, we stay upon your leisure.
          |MACBETH
          |Give me your favour: my dull brain was wrought
          |With things forgotten. Kind gentlemen, your pains
          |Are register'd where every day I turn
          |The leaf to read them. Let us toward the king.
          |Think upon what hath chanced, and, at more time,
          |The interim having weigh'd it, let us speak
          |Our free hearts each to other.
          |BANQUO
          |Very gladly.
          |MACBETH
          |Till then, enough. Come, friends.
          |Exeunt
          |
          |SCENE IV. Forres. The palace.
          |
          |Flourish. Enter DUNCAN, MALCOLM, DONALBAIN, LENNOX, and Attendants
          |DUNCAN
          |Is execution done on Cawdor? Are not
          |Those in commission yet return'd?
          |MALCOLM
          |My liege,
          |They are not yet come back. But I have spoke
          |With one that saw him die: who did report
          |That very frankly he confess'd his treasons,
          |Implored your highness' pardon and set forth
          |A deep repentance: nothing in his life
          |Became him like the leaving it; he died
          |As one that had been studied in his death
          |To throw away the dearest thing he owed,
          |As 'twere a careless trifle.
          |DUNCAN
          |There's no art
          |To find the mind's construction in the face:
          |He was a gentleman on whom I built
          |An absolute trust.
          |Enter MACBETH, BANQUO, ROSS, and ANGUS
          |
          |O worthiest cousin!
          |The sin of my ingratitude even now
          |Was heavy on me: thou art so far before
          |That swiftest wing of recompense is slow
          |To overtake thee. Would thou hadst less deserved,
          |That the proportion both of thanks and payment
          |Might have been mine! only I have left to say,
          |More is thy due than more than all can pay.
          |MACBETH
          |The service and the loyalty I owe,
          |In doing it, pays itself. Your highness' part
          |Is to receive our duties; and our duties
          |Are to your throne and state children and servants,
          |Which do but what they should, by doing every thing
          |Safe toward your love and honour.
          |DUNCAN
          |Welcome hither:
          |I have begun to plant thee, and will labour
          |To make thee full of growing. Noble Banquo,
          |That hast no less deserved, nor must be known
          |No less to have done so, let me enfold thee
          |And hold thee to my heart.
          |BANQUO
          |There if I grow,
          |The harvest is your own.
          |DUNCAN
          |My plenteous joys,
          |Wanton in fulness, seek to hide themselves
          |In drops of sorrow. Sons, kinsmen, thanes,
          |And you whose places are the nearest, know
          |We will establish our estate upon
          |Our eldest, Malcolm, whom we name hereafter
          |The Prince of Cumberland; which honour must
          |Not unaccompanied invest him only,
          |But signs of nobleness, like stars, shall shine
          |On all deservers. From hence to Inverness,
          |And bind us further to you.
          |MACBETH
          |The rest is labour, which is not used for you:
          |I'll be myself the harbinger and make joyful
          |The hearing of my wife with your approach;
          |So humbly take my leave.
          |DUNCAN
          |My worthy Cawdor!
          |MACBETH
          |[Aside] The Prince of Cumberland! that is a step
          |On which I must fall down, or else o'erleap,
          |For in my way it lies. Stars, hide your fires;
          |Let not light see my black and deep desires:
          |The eye wink at the hand; yet let that be,
          |Which the eye fears, when it is done, to see.
          |Exit
          |
          |DUNCAN
          |True, worthy Banquo; he is full so valiant,
          |And in his commendations I am fed;
          |It is a banquet to me. Let's after him,
          |Whose care is gone before to bid us welcome:
          |It is a peerless kinsman.
          |Flourish. Exeunt
          |
          |SCENE V. Inverness. Macbeth's castle.
          |
          |Enter LADY MACBETH, reading a letter
          |LADY MACBETH
          |'They met me in the day of success: and I have
          |learned by the perfectest report, they have more in
          |them than mortal knowledge. When I burned in desire
          |to question them further, they made themselves air,
          |into which they vanished. Whiles I stood rapt in
          |the wonder of it, came missives from the king, who
          |all-hailed me 'Thane of Cawdor;' by which title,
          |before, these weird sisters saluted me, and referred
          |me to the coming on of time, with 'Hail, king that
          |shalt be!' This have I thought good to deliver
          |thee, my dearest partner of greatness, that thou
          |mightst not lose the dues of rejoicing, by being
          |ignorant of what greatness is promised thee. Lay it
          |to thy heart, and farewell.'
          |Glamis thou art, and Cawdor; and shalt be
          |What thou art promised: yet do I fear thy nature;
          |It is too full o' the milk of human kindness
          |To catch the nearest way: thou wouldst be great;
          |Art not without ambition, but without
          |The illness should attend it: what thou wouldst highly,
          |That wouldst thou holily; wouldst not play false,
          |And yet wouldst wrongly win: thou'ldst have, great Glamis,
          |That which cries 'Thus thou must do, if thou have it;
          |And that which rather thou dost fear to do
          |Than wishest should be undone.' Hie thee hither,
          |That I may pour my spirits in thine ear;
          |And chastise with the valour of my tongue
          |All that impedes thee from the golden round,
          |Which fate and metaphysical aid doth seem
          |To have thee crown'd withal.
          |Enter a Messenger
          |
          |What is your tidings?
          |Messenger
          |The king comes here to-night.
          |LADY MACBETH
          |Thou'rt mad to say it:
          |Is not thy master with him? who, were't so,
          |Would have inform'd for preparation.
          |Messenger
          |So please you, it is true: our thane is coming:
          |One of my fellows had the speed of him,
          |Who, almost dead for breath, had scarcely more
          |Than would make up his message.
          |LADY MACBETH
          |Give him tending;
          |He brings great news.
          |Exit Messenger
          |
          |The raven himself is hoarse
          |That croaks the fatal entrance of Duncan
          |Under my battlements. Come, you spirits
          |That tend on mortal thoughts, unsex me here,
          |And fill me from the crown to the toe top-full
          |Of direst cruelty! make thick my blood;
          |Stop up the access and passage to remorse,
          |That no compunctious visitings of nature
          |Shake my fell purpose, nor keep peace between
          |The effect and it! Come to my woman's breasts,
          |And take my milk for gall, you murdering ministers,
          |Wherever in your sightless substances
          |You wait on nature's mischief! Come, thick night,
          |And pall thee in the dunnest smoke of hell,
          |That my keen knife see not the wound it makes,
          |Nor heaven peep through the blanket of the dark,
          |To cry 'Hold, hold!'
          |Enter MACBETH
          |
          |Great Glamis! worthy Cawdor!
          |Greater than both, by the all-hail hereafter!
          |Thy letters have transported me beyond
          |This ignorant present, and I feel now
          |The future in the instant.
          |MACBETH
          |My dearest love,
          |Duncan comes here to-night.
          |LADY MACBETH
          |And when goes hence?
          |MACBETH
          |To-morrow, as he purposes.
          |LADY MACBETH
          |O, never
          |Shall sun that morrow see!
          |Your face, my thane, is as a book where men
          |May read strange matters. To beguile the time,
          |Look like the time; bear welcome in your eye,
          |Your hand, your tongue: look like the innocent flower,
          |But be the serpent under't. He that's coming
          |Must be provided for: and you shall put
          |This night's great business into my dispatch;
          |Which shall to all our nights and days to come
          |Give solely sovereign sway and masterdom.
          |MACBETH
          |We will speak further.
          |LADY MACBETH
          |Only look up clear;
          |To alter favour ever is to fear:
          |Leave all the rest to me.
          |Exeunt
          |
          |SCENE VI. Before Macbeth's castle.
          |
          |Hautboys and torches. Enter DUNCAN, MALCOLM, DONALBAIN, BANQUO, LENNOX, MACDUFF, ROSS, ANGUS, and Attendants
          |DUNCAN
          |This castle hath a pleasant seat; the air
          |Nimbly and sweetly recommends itself
          |Unto our gentle senses.
          |BANQUO
          |This guest of summer,
          |The temple-haunting martlet, does approve,
          |By his loved mansionry, that the heaven's breath
          |Smells wooingly here: no jutty, frieze,
          |Buttress, nor coign of vantage, but this bird
          |Hath made his pendent bed and procreant cradle:
          |Where they most breed and haunt, I have observed,
          |The air is delicate.
          |Enter LADY MACBETH
          |
          |DUNCAN
          |See, see, our honour'd hostess!
          |The love that follows us sometime is our trouble,
          |Which still we thank as love. Herein I teach you
          |How you shall bid God 'ild us for your pains,
          |And thank us for your trouble.
          |LADY MACBETH
          |All our service
          |In every point twice done and then done double
          |Were poor and single business to contend
          |Against those honours deep and broad wherewith
          |Your majesty loads our house: for those of old,
          |And the late dignities heap'd up to them,
          |We rest your hermits.
          |DUNCAN
          |Where's the thane of Cawdor?
          |We coursed him at the heels, and had a purpose
          |To be his purveyor: but he rides well;
          |And his great love, sharp as his spur, hath holp him
          |To his home before us. Fair and noble hostess,
          |We are your guest to-night.
          |LADY MACBETH
          |Your servants ever
          |Have theirs, themselves and what is theirs, in compt,
          |To make their audit at your highness' pleasure,
          |Still to return your own.
          |DUNCAN
          |Give me your hand;
          |Conduct me to mine host: we love him highly,
          |And shall continue our graces towards him.
          |By your leave, hostess.
          |Exeunt
          |
          |SCENE VII. Macbeth's castle.
          |
          |Hautboys and torches. Enter a Sewer, and divers Servants with dishes and service, and pass over the stage. Then enter MACBETH
          |MACBETH
          |If it were done when 'tis done, then 'twere well
          |It were done quickly: if the assassination
          |Could trammel up the consequence, and catch
          |With his surcease success; that but this blow
          |Might be the be-all and the end-all here,
          |But here, upon this bank and shoal of time,
          |We'ld jump the life to come. But in these cases
          |We still have judgment here; that we but teach
          |Bloody instructions, which, being taught, return
          |To plague the inventor: this even-handed justice
          |Commends the ingredients of our poison'd chalice
          |To our own lips. He's here in double trust;
          |First, as I am his kinsman and his subject,
          |Strong both against the deed; then, as his host,
          |Who should against his murderer shut the door,
          |Not bear the knife myself. Besides, this Duncan
          |Hath borne his faculties so meek, hath been
          |So clear in his great office, that his virtues
          |Will plead like angels, trumpet-tongued, against
          |The deep damnation of his taking-off;
          |And pity, like a naked new-born babe,
          |Striding the blast, or heaven's cherubim, horsed
          |Upon the sightless couriers of the air,
          |Shall blow the horrid deed in every eye,
          |That tears shall drown the wind. I have no spur
          |To prick the sides of my intent, but only
          |Vaulting ambition, which o'erleaps itself
          |And falls on the other.
          |Enter LADY MACBETH
          |
          |How now! what news?
          |LADY MACBETH
          |He has almost supp'd: why have you left the chamber?
          |MACBETH
          |Hath he ask'd for me?
          |LADY MACBETH
          |Know you not he has?
          |MACBETH
          |We will proceed no further in this business:
          |He hath honour'd me of late; and I have bought
          |Golden opinions from all sorts of people,
          |Which would be worn now in their newest gloss,
          |Not cast aside so soon.
          |LADY MACBETH
          |Was the hope drunk
          |Wherein you dress'd yourself? hath it slept since?
          |And wakes it now, to look so green and pale
          |At what it did so freely? From this time
          |Such I account thy love. Art thou afeard
          |To be the same in thine own act and valour
          |As thou art in desire? Wouldst thou have that
          |Which thou esteem'st the ornament of life,
          |And live a coward in thine own esteem,
          |Letting 'I dare not' wait upon 'I would,'
          |Like the poor cat i' the adage?
          |MACBETH
          |Prithee, peace:
          |I dare do all that may become a man;
          |Who dares do more is none.
          |LADY MACBETH
          |What beast was't, then,
          |That made you break this enterprise to me?
          |When you durst do it, then you were a man;
          |And, to be more than what you were, you would
          |Be so much more the man. Nor time nor place
          |Did then adhere, and yet you would make both:
          |They have made themselves, and that their fitness now
          |Does unmake you. I have given suck, and know
          |How tender 'tis to love the babe that milks me:
          |I would, while it was smiling in my face,
          |Have pluck'd my nipple from his boneless gums,
          |And dash'd the brains out, had I so sworn as you
          |Have done to this.
          |MACBETH
          |If we should fail?
          |LADY MACBETH
          |We fail!
          |But screw your courage to the sticking-place,
          |And we'll not fail. When Duncan is asleep--
          |Whereto the rather shall his day's hard journey
          |Soundly invite him--his two chamberlains
          |Will I with wine and wassail so convince
          |That memory, the warder of the brain,
          |Shall be a fume, and the receipt of reason
          |A limbeck only: when in swinish sleep
          |Their drenched natures lie as in a death,
          |What cannot you and I perform upon
          |The unguarded Duncan? what not put upon
          |His spongy officers, who shall bear the guilt
          |Of our great quell?
          |MACBETH
          |Bring forth men-children only;
          |For thy undaunted mettle should compose
          |Nothing but males. Will it not be received,
          |When we have mark'd with blood those sleepy two
          |Of his own chamber and used their very daggers,
          |That they have done't?
          |LADY MACBETH
          |Who dares receive it other,
          |As we shall make our griefs and clamour roar
          |Upon his death?
          |MACBETH
          |I am settled, and bend up
          |Each corporal agent to this terrible feat.
          |Away, and mock the time with fairest show:
          |False face must hide what the false heart doth know.
        """.stripMargin
      for (n <- 1 to 100) {
        val msg: List[Byte] = List.fill(n)(macbeth).map(_.getBytes).flatten
        println(s"num bytes: ${msg.size}")
        val publishFrame = PublishFrame(header, topic, 10, ByteVector(msg))
        Codec[Frame].decode(Codec[Frame].encode(publishFrame).require) should succeedWith(DecodeResult(publishFrame, bin""))
      }

      success
    }

    "Perform round trip encoding/decoding of a valid input with a QoS equals to 0" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val topic = "a/b"
      val publishFrame = PublishFrame(header, topic, 0, ByteVector("Hello world".getBytes))

      Codec[Frame].decode(Codec[Frame].encode(publishFrame).require) should succeedWith(DecodeResult(publishFrame, bin""))
    }
  }
}
